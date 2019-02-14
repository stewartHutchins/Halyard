/*
 * Copyright 2016 Merck Sharp & Dohme Corp. a subsidiary of Merck & Co.,
 * Inc., Kenilworth, NJ, USA.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.msd.gin.halyard.sail;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.msd.gin.halyard.common.HalyardTableUtils;
import com.msd.gin.halyard.optimizers.HalyardEvaluationStatistics;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.BufferedMutator;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.Table;
import org.eclipse.rdf4j.IsolationLevel;
import org.eclipse.rdf4j.IsolationLevels;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Namespace;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleNamespace;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.algebra.evaluation.federation.FederatedServiceResolver;
import org.eclipse.rdf4j.sail.Sail;
import org.eclipse.rdf4j.sail.SailConnection;
import org.eclipse.rdf4j.sail.SailException;

/**
 * HBaseSail is the RDF Storage And Inference Layer (SAIL) implementation on top of Apache HBase.
 * It implements the interfaces - {@code Sail, SailConnection} and {@code FederatedServiceResolver}. Currently federated queries are
 * only supported for queries across multiple graphs in one Halyard database.
 * @author Adam Sotona (MSD)
 */
public class HBaseSail implements Sail, FederatedServiceResolver {

    /**
     * Ticker is a simple service interface that is notified when some data are processed.
     * It's purpose is to notify a caller (for example MapReduce task) that the execution is still alive.
     */
    public interface Ticker {

        /**
         * This method is called whenever a new Statement is populated from HBase.
         */
        public void tick();
    }

	/**
	 * Interface to make it easy to change connection implementations.
	 */
	public interface ConnectionFactory {
		SailConnection createConnection(HBaseSail sail);
	}

    private static final long STATUS_CACHING_TIMEOUT = 60000l;

    private final Configuration config; //the configuration of the HBase database
    final String tableName;
    final boolean create;
    final boolean pushStrategy;
    final int splitBits;
	protected HalyardEvaluationStatistics statistics;
    final int evaluationTimeout;
    private boolean readOnly = false;
    private long readOnlyTimestamp = -1;
    final String elasticIndexURL;
    final Ticker ticker;
	final ConnectionFactory connFactory;
	Connection hConnection;
	final boolean hConnectionIsShared; //whether a Connection is provided or we need to create our own

    final Map<String, Namespace> namespaces = new ConcurrentHashMap<>();
	private final Cache<String, SailFederatedService> federatedServices = CacheBuilder.newBuilder().maximumSize(100)
			.removalListener((evt) -> ((SailFederatedService) evt.getValue()).shutdown()).build();


    private HBaseSail(Connection conn, Configuration config, String tableName, boolean create, int splitBits, boolean pushStrategy, int evaluationTimeout, String elasticIndexURL, Ticker ticker, ConnectionFactory connFactory) {
    	this.hConnection = conn;
    	this.hConnectionIsShared = (conn != null);
        this.config = config;
        this.tableName = tableName;
        this.create = create;
        this.splitBits = splitBits;
        this.pushStrategy = pushStrategy;
        this.evaluationTimeout = evaluationTimeout;
        this.elasticIndexURL = elasticIndexURL;
        this.ticker = ticker;
		this.connFactory = connFactory;
    }

    /**
	 * Construct HBaseSail object with given arguments.
	 * 
	 * @param config Hadoop Configuration to access HBase
	 * @param tableName HBase table name used to store data
	 * @param create boolean option to create the table if it does not exist
	 * @param splitBits int number of bits used for the calculation of HTable region pre-splits (applies for new tables only)
	 * @param pushStrategy boolean option to use {@link com.msd.gin.halyard.strategy.HalyardEvaluationStrategy} instead of
	 * {@link org.eclipse.rdf4j.query.algebra.evaluation.impl.StrictEvaluationStrategy}
	 * @param evaluationTimeout int timeout in seconds for each query evaluation, negative values mean no timeout
	 * @param elasticIndexURL String optional ElasticSearch index URL
	 * @param ticker optional Ticker callback for keep-alive notifications
	 * @param connFactory {@link ConnectionFactory} for creating connections
	 */
    public HBaseSail(Configuration config, String tableName, boolean create, int splitBits, boolean pushStrategy, int evaluationTimeout, String elasticIndexURL, Ticker ticker, ConnectionFactory connFactory) {
    	this(null, config, tableName, create, splitBits, pushStrategy, evaluationTimeout, elasticIndexURL, ticker, connFactory);
    }

    public HBaseSail(Connection conn, String tableName, boolean create, int splitBits, boolean pushStrategy, int evaluationTimeout, String elasticIndexURL, Ticker ticker) {
		this(conn, conn.getConfiguration(), tableName, create, splitBits, pushStrategy, evaluationTimeout, elasticIndexURL, ticker, HBaseSailConnection.Factory.INSTANCE);
	}

    public HBaseSail(Configuration config, String tableName, boolean create, int splitBits, boolean pushStrategy, int evaluationTimeout, String elasticIndexURL, Ticker ticker) {
		this(null, config, tableName, create, splitBits, pushStrategy, evaluationTimeout, elasticIndexURL, ticker, HBaseSailConnection.Factory.INSTANCE);
	}

    /**
     * Not used in Halyard
     */
    @Override
    public void setDataDir(File dataDir) {
    }

    /**
     * Not used in Halyard
     */
    @Override
    public File getDataDir() {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns a new HTable connection.
     */
	Table getTable() {
        try {
			return HalyardTableUtils.getTable(hConnection, tableName, create, splitBits);
		} catch (IOException e) {
			throw new SailException(e);
		}
    }

	BufferedMutator getBufferedMutator(Table table) {
		try {
			return hConnection.getBufferedMutator(table.getName());
		} catch (IOException e) {
			throw new SailException(e);
		}
	}

	@Override
    public void initialize() throws SailException { //initialize the SAIL
    	if (!hConnectionIsShared) {
			// connections are thread-safe and very heavyweight - only do it once
        	if (hConnection != null) {
        		throw new IllegalStateException("Sail has already been initialized");
        	}
			try {
				hConnection = HalyardTableUtils.getConnection(config);
			} catch (IOException e) {
				throw new SailException(e);
			}
    	}

    	try (SailConnection conn = getConnection()) {
            //Iterate over statements relating to namespaces and add them to the namespace map.
            try (CloseableIteration<? extends Statement, SailException> nsIter = conn.getStatements(null, HALYARD.NAMESPACE_PREFIX_PROPERTY, null, true)) {
                while (nsIter.hasNext()) {
                    Statement st = nsIter.next();
                    if (st.getObject() instanceof Literal) {
                        String prefix = st.getObject().stringValue();
                        String name = st.getSubject().stringValue();
                        namespaces.put(prefix, new SimpleNamespace(prefix, name));
                    }
                }
            }
        }

		this.statistics = new HalyardEvaluationStatistics(new HalyardStatsBasedStatementPatternCardinalityCalculator(this), (String service) -> {
			SailFederatedService fedServ = getService(service);
			return fedServ != null ? ((HBaseSail) fedServ.getSail()).statistics : null;
		});
    }

    @Override
	public SailFederatedService getService(String serviceUrl) throws QueryEvaluationException {
        //provide a service to query over Halyard graphs. Remote service queries are not supported.
        if (serviceUrl.startsWith(HALYARD.NAMESPACE)) {
            String federatedTable = serviceUrl.substring(HALYARD.NAMESPACE.length());
            SailFederatedService federatedService;
            try {
				federatedService = federatedServices.get(federatedTable, () -> {
				    HBaseSail sail = new HBaseSail(hConnection, config, federatedTable, false, 0, true, evaluationTimeout, null, ticker, HBaseSailConnection.Factory.INSTANCE);
				    sail.initialize();
				    return new SailFederatedService(sail);
				});
			}
			catch (ExecutionException e) {
				if (e.getCause() instanceof RuntimeException) {
					throw (RuntimeException) e.getCause();
				} else {
					throw new AssertionError(e.getClass());
				}
			}
            return federatedService;
        } else {
            throw new QueryEvaluationException("Unsupported service URL: " + serviceUrl);
        }
    }

    @Override
    public void shutDown() throws SailException { //release resources
		if (!hConnectionIsShared && hConnection != null) {
			try {
				hConnection.close();
				hConnection = null;
			} catch (IOException e) {
				throw new SailException(e);
			}
		}

		federatedServices.invalidateAll(); // release the references to the services
    }

    @Override
    public boolean isWritable() throws SailException {
        if (readOnlyTimestamp + STATUS_CACHING_TIMEOUT < System.currentTimeMillis()) {
			try (Table table = getTable()) {
        		readOnly = table.getTableDescriptor().isReadOnly();
	            readOnlyTimestamp = System.currentTimeMillis();
	        } catch (IOException ex) {
	            throw new SailException(ex);
	        }
	    }
        return !readOnly;
    }

    @Override
	public SailConnection getConnection() throws SailException {
		if (hConnection == null) {
			throw new IllegalStateException("Sail is not initialized or has been shut down");
		}
		return connFactory.createConnection(this);
    }

    @Override
    public ValueFactory getValueFactory() {
        return SimpleValueFactory.getInstance();
    }

    @Override
    public List<IsolationLevel> getSupportedIsolationLevels() {
        return Collections.singletonList((IsolationLevel) IsolationLevels.NONE); //limited by HBase's capabilities
    }

    @Override
    public IsolationLevel getDefaultIsolationLevel() {
        return IsolationLevels.NONE;
    }
}
