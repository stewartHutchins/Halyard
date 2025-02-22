/*
 * Copyright 2018 Merck Sharp & Dohme Corp. a subsidiary of Merck & Co.,
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
package com.msd.gin.halyard.tools;

import com.msd.gin.halyard.query.algebra.AbstractExtendedQueryModelVisitor;
import com.msd.gin.halyard.query.BindingSetPipe;
import com.msd.gin.halyard.repository.HBaseRepository;
import com.msd.gin.halyard.sail.ElasticSettings;
import com.msd.gin.halyard.sail.HBaseSail;
import com.msd.gin.halyard.sail.HBaseSailConnection;

import java.io.IOException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import org.apache.commons.cli.CommandLine;
import org.apache.hadoop.hbase.KeyValue;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.common.iteration.EmptyIteration;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.Binding;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.BooleanQuery;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.GraphQuery;
import org.eclipse.rdf4j.query.Operation;
import org.eclipse.rdf4j.query.Query;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.Update;
import org.eclipse.rdf4j.query.algebra.BinaryTupleOperator;
import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.QueryRoot;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.VariableScopeChange;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryEvaluationStep;
import org.eclipse.rdf4j.query.parser.QueryParserUtil;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.sail.SailException;

/**
 * Command line tool for various Halyard profiling
 * @author Adam Sotona (MSD)
 */
public final class HalyardProfile extends AbstractHalyardTool {

    public HalyardProfile() {
        super(
            "profile",
            "Halyard Profile is a command-line tool designed to profile SPARQL query performance within the actual Halyard environment. Actually it is limited to the statical analysis only.",
            "Example: halyard profile -s my_dataset -q 'select * where {?s owl:sameAs ?o}'"
        );
        addOption("s", "source-dataset", "dataset_table", "Source HBase table with Halyard RDF store", true, true);
        addOption("q", "query", "sparql_query", "SPARQL query to profile", false, true);
        addOption("i", "elastic-index", "elastic_index_url", ElasticSettings.ELASTIC_INDEX_URL, "Optional ElasticSearch index URL", false, true);
        addKeyValueOption("$", null, "binding=value", BINDING_PROPERTY_PREFIX, "Optionally specify bindings");
    }

    @Override
    public int run(CommandLine cmd) throws Exception {
		String queryString = cmd.getOptionValue('q');
		String table = cmd.getOptionValue('s');
		configureString(cmd, 'i', null);
		configureBindings(cmd, '$');
		if (queryString == null) {
			System.out.println("Reading query from stdin (ctrl+D when done)...");
			queryString = new String(System.in.readAllBytes()); // use platform encoding here!
		}

		HBaseSail sail = new HBaseSail(getConf(), table, false, 0, true, 0, ElasticSettings.from(getConf()), null, new HBaseSail.SailConnectionFactory() {
			@Override
			public HBaseSailConnection createConnection(HBaseSail sail) throws IOException {
				return new HBaseSailConnection(sail) {
					private final NumberFormat cardinalityFormatter = DecimalFormat.getNumberInstance();

					@Override
					protected void put(KeyValue kv) throws IOException {
						// do nothing
					}
					@Override
					protected void delete(KeyValue kv) throws IOException {
						// do nothing
					}
					@Override
		            public CloseableIteration<BindingSet> evaluate(TupleExpr tupleExpr, Dataset dataset, BindingSet bindings, boolean includeInferred) throws SailException {
		                System.out.println(toMessage("Original query:", tupleExpr));
		                return super.evaluate(tupleExpr, dataset, bindings, includeInferred);
		            }
		            @Override
		            protected CloseableIteration<BindingSet> evaluateInternal(TupleExpr optimizedTupleExpr, QueryEvaluationStep step) {
		                System.out.println(toMessage("Optimized query:", optimizedTupleExpr));
		                return new EmptyIteration<>();
		            }
					@Override
					public void evaluate(Consumer<BindingSet> handler, final TupleExpr tupleExpr, final Dataset dataset, final BindingSet bindings, final boolean includeInferred) {
		                System.out.println(toMessage("Original query:", tupleExpr));
		                super.evaluate(handler, tupleExpr, dataset, bindings, includeInferred);
					}
					@Override
					protected void evaluateInternal(Consumer<BindingSet> handler, TupleExpr optimizedTupleExpr, QueryEvaluationStep step) throws QueryEvaluationException {
		                System.out.println(toMessage("Optimized query:", optimizedTupleExpr));
					}
					@Override
					public void evaluate(BindingSetPipe pipe, final TupleExpr tupleExpr, final Dataset dataset, final BindingSet bindings, final boolean includeInferred) {
		                System.out.println(toMessage("Original query:", tupleExpr));
		                super.evaluate(pipe, tupleExpr, dataset, bindings, includeInferred);
					}
					@Override
					protected void evaluateInternal(BindingSetPipe pipe, TupleExpr optimizedTupleExpr, QueryEvaluationStep step) throws QueryEvaluationException {
		                System.out.println(toMessage("Optimized query:", optimizedTupleExpr));
					}
		            private String toMessage(String msg, TupleExpr expr) {
		                final Map<TupleExpr, Double> cardMap = new HashMap<>();
		                if (expr instanceof QueryRoot) {
		                    expr = ((QueryRoot)expr).getArg();
		                }
						sail.getStatistics().updateCardinalityMap(expr, Collections.emptySet(), Collections.emptySet(), cardMap, true);
		                final StringBuilder buf = new StringBuilder(256);
		                buf.append(msg).append(System.lineSeparator());
		                expr.visit(new AbstractExtendedQueryModelVisitor<RuntimeException>() {
		                	private final String INDENT = "    ";
		                    private int indentLevel = 0;
		                    @Override
		                    protected void meetNode(QueryModelNode node) {
		                    	if (node instanceof VariableScopeChange && ((VariableScopeChange)node).isVariableScopeChange()) {
		                    		writeScopeChange(node);
		                    	} else {
		                    		writeNode(node);
		                    	}
		                    }
		                    private void writeScopeChange(QueryModelNode node) {
		                    	writeIndent();
		                    	buf.append("{").append(System.lineSeparator());
		                        indentLevel++;
		                        writeNode(node);
		                        indentLevel--;
		                    	writeIndent();
		                    	buf.append("}").append(System.lineSeparator());
		                    }
		                    private void writeNode(QueryModelNode node) {
		                    	writeIndent();
		                        buf.append(node.getSignature());
		                        StringBuilder statsBuf = new StringBuilder(100);
		                        Double card = cardMap.get(node);
		                        String sep = "";
		                        if (card != null) {
		                            statsBuf.append(sep).append("cardinality = ").append(cardinalityFormatter.format(card));
		                            sep = ", ";
		                        }
		                        double sizeEstimate = node.getResultSizeEstimate();
		                        if (sizeEstimate >= 0.0) {
		                        	statsBuf.append(sep).append("size = ").append(cardinalityFormatter.format(sizeEstimate));
		                            sep = ", ";
		                        }
		                        double costEstimate = node.getCostEstimate();
		                        if (costEstimate >= 0.0) {
		                        	statsBuf.append(sep).append("cost = ").append(cardinalityFormatter.format(costEstimate));
		                            sep = ", ";
		                        }
		                        String algorithm = (node instanceof BinaryTupleOperator) ? ((BinaryTupleOperator)node).getAlgorithmName() : null;
		                        if (algorithm != null) {
		                        	statsBuf.append(sep).append("algorithm = ").append(algorithm);
		                            sep = ", ";
		                        }
		                        if (statsBuf.length() > 0) {
		                            buf.append(" [").append(statsBuf).append("]");
		                        }
	                            buf.append(System.lineSeparator());
		                        indentLevel++;
		                        super.meetNode(node);
		                        indentLevel--;
		                    }
		                    private void writeIndent() {
		                        for (int i = 0; i < indentLevel; i++) {
	                                buf.append(INDENT);
		                        }
		                    }
		                });
		                return buf.toString();
		            }
				};
			}

		});

		HBaseRepository repo = new HBaseRepository(sail);
        repo.init();
        BindingSet bindings = AbstractHalyardTool.getBindings(getConf(), repo.getValueFactory());
        try {
        	try(RepositoryConnection conn = repo.getConnection()) {
	        	System.out.println(queryString);
	        	System.out.println();
	        	String strippedQuery = QueryParserUtil.removeSPARQLQueryProlog(queryString).toUpperCase(Locale.ROOT);
				if (strippedQuery.startsWith("SELECT") || strippedQuery.startsWith("CONSTRUCT")
						|| strippedQuery.startsWith("DESCRIBE") || strippedQuery.startsWith("ASK")) {
					Query q = conn.prepareQuery(queryString);
					setBindings(q, bindings, repo.getValueFactory());
					if (q instanceof BooleanQuery) {
					    ((BooleanQuery)q).evaluate();
					} else if (q instanceof TupleQuery) {
					    ((TupleQuery)q).evaluate();
					} else if (q instanceof GraphQuery) {
					    ((GraphQuery)q).evaluate();
					}
				} else {
					Update u = conn.prepareUpdate(queryString);
					setBindings(u, bindings, repo.getValueFactory());
					u.execute();
				}
        	}
        } finally {
            repo.shutDown();
        }
        return 0;
    }

    static void setBindings(Operation op, BindingSet bindings, ValueFactory vf) {
    	for (Binding b : bindings) {
    		op.setBinding(b.getName(), b.getValue());
    	}
    }
}
