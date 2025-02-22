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
package com.msd.gin.halyard.common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.client.TableDescriptor;
import org.apache.hadoop.hbase.client.TableDescriptorBuilder;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.FirstKeyOnlyFilter;
import org.apache.hadoop.hbase.filter.KeyOnlyFilter;
import org.apache.hadoop.hbase.util.Bytes;
import org.eclipse.rdf4j.model.IRI;

/**
 * Core Halyard utility class performing RDF to HBase mappings and base HBase table and key management. The methods of this class define how
 * Halyard stores and finds data in HBase. This class also provides several constants that define the key encoding.
 *
 * @author Adam Sotona (MSD)
 */
public final class HalyardTableUtils {

    private static final byte[] CONFIG_ROW_KEY = new byte[] {(byte) 0xff};
    private static final byte[] CONFIG_COL = Bytes.toBytes("config");

	static final int READ_VERSIONS = 1;
	private static final long REGION_MAX_FILESIZE = 10000000000l;  // 10GB
    private static final String REGION_SPLIT_POLICY = "org.apache.hadoop.hbase.regionserver.ConstantSizeRegionSplitPolicy";

    private HalyardTableUtils() {}

	/**
	 * Helper method which locates or creates and returns the specified Table used for triple/ quad storage. The table may be pre-split into regions (rather than HBase's default of
	 * starting with 1). For a discussion of pre-splits take a look at <a href= "https://hortonworks.com/blog/apache-hbase-region-splitting-and-merging/">this article</a>
	 * 
	 * @param conn Connection to the cluster running HBase
	 * @param tableName String table name
	 * @param create boolean option to create the table if does not exist
	 * @param splitBits int number of bits used for calculation of Table region pre-splits (applies for new tables only).
	 * Must be between 0 and 16 (or -1 for no splits). Higher values generate more splits.
	 * @throws IOException throws IOException in case of any HBase IO problems
	 * @return the org.apache.hadoop.hbase.client.Table
	 */
	public static Table getTable(Connection conn, String tableName, boolean create, int splitBits) throws IOException {
		TableName htableName = TableName.valueOf(tableName);
        if (create && !tableExists(conn, htableName)) {
            return createTable(conn, htableName, splitBits);
        } else {
        	return conn.getTable(htableName);
        }
    }

	public static boolean tableExists(Connection conn, TableName htableName) throws IOException {
		try (Admin admin = conn.getAdmin()) {
			return admin.tableExists(htableName);
		}
	}

	/**
	 * Creates a HBase table for use with Halyard.
	 * @param conn HBase server connection.
	 * @param htableName name of table to create.
	 * @param splitBits -1 for no splits.
	 * @return the created table
	 * @throws IOException
	 */
	public static Table createTable(Connection conn, TableName htableName, int splitBits) throws IOException {
		Configuration conf = conn.getConfiguration();
		RDFFactory rdfFactory = RDFFactory.create(conf);
		StatementIndices indices = new StatementIndices(conf, rdfFactory);
        return createTable(conn, htableName, splitBits < 0 ? null : calculateSplits(splitBits, true, indices));
	}

	public static Table createTable(Connection conn, TableName htableName, @Nullable byte[][] splits) throws IOException {
		Configuration conf = conn.getConfiguration();
		try (Admin admin = conn.getAdmin()) {
			TableDescriptor td = TableDescriptorBuilder.newBuilder(htableName)
				.setColumnFamily(ColumnFamilyConfig.createColumnFamilyDesc(conf))
				.setMaxFileSize(REGION_MAX_FILESIZE)
				.setRegionSplitPolicyClassName(REGION_SPLIT_POLICY)
				.build();
			admin.createTable(td, splits);
		}
		Table table = conn.getTable(htableName);
		HalyardTableConfiguration halyardConfig = new HalyardTableConfiguration(conf);
		writeConfig(table, halyardConfig);
		return table;
	}

	static void writeConfig(Table table, HalyardTableConfiguration halyardConfig) throws IOException {
		ByteArrayOutputStream bout = new ByteArrayOutputStream(1024);
		halyardConfig.writeXml(bout);
		Put configPut = new Put(CONFIG_ROW_KEY)
			.addColumn(ColumnFamilyConfig.CF_NAME, CONFIG_COL, bout.toByteArray());
		table.put(configPut);
	}

	public static Configuration readConfig(KeyspaceConnection conn) throws IOException {
		Get getConfig = new Get(CONFIG_ROW_KEY)
				.addColumn(ColumnFamilyConfig.CF_NAME, CONFIG_COL);
		Result res = conn.get(getConfig);
		if (res == null) {
			throw new IOException("No config found");
		}
		Cell[] cells = res.rawCells();
		if (cells == null || cells.length == 0) {
			throw new IOException("No config found");
		}
		Cell cell = cells[0];
		Configuration halyardConf = new Configuration(false);
		ByteArrayInputStream bin = new ByteArrayInputStream(cell.getValueArray(), cell.getValueOffset(), cell.getValueLength());
		halyardConf.addResource(bin, "from table/snapshot");
		return halyardConf;
	}

	public static Connection getConnection(Configuration config) throws IOException {
		Configuration cfg = HBaseConfiguration.create(config);
		cfg.setLong(HConstants.HBASE_CLIENT_SCANNER_TIMEOUT_PERIOD, 3600000l);
		return ConnectionFactory.createConnection(cfg);
	}

	/**
	 * Truncates Table while preserving the region pre-splits and any config.
	 * 
	 * @param conn connection to cluster
	 * @param tableName Table to truncate
	 * @throws IOException throws IOException in case of any HBase IO problems
	 */
	public static void clearStatements(Connection conn, TableName tableName) throws IOException {
		Get getConfig = new Get(HalyardTableUtils.CONFIG_ROW_KEY);
		Result config;
		try (Table table = conn.getTable(tableName)) {
			config = table.get(getConfig);
		}
		try (Admin admin = conn.getAdmin()) {
			admin.disableTable(tableName);
			admin.truncateTable(tableName, true);
		}
		if (!config.isEmpty()) {
			Put putConfig = new Put(HalyardTableUtils.CONFIG_ROW_KEY);
			for (Cell cell : config.rawCells()) {
				putConfig.add(cell);
			}
			try (Table table = conn.getTable(tableName)) {
				table.put(putConfig);
			}
		}
    }

	public static void deleteTable(Connection conn, TableName table) throws IOException {
		try (Admin admin = conn.getAdmin()) {
			admin.disableTable(table);
			admin.deleteTable(table);
		}
    }

    public static Keyspace getKeyspace(Configuration conf, String sourceName, String restorePathName) throws IOException {
    	TableName tableName;
    	Path restorePath;
        if (restorePathName != null) {
        	tableName = null;
        	restorePath = new Path(restorePathName);
        } else {
        	tableName = TableName.valueOf(sourceName);
        	restorePath = null;
        }
        return getKeyspace(conf, null, tableName, sourceName, restorePath);
    }

    public static Keyspace getKeyspace(Configuration conf, Connection conn, TableName tableName, String snapshotName, Path restorePath) throws IOException {
    	Keyspace keyspace;
    	if (tableName != null) {
    		if (conn != null) {
    			keyspace = new TableKeyspace(conn, tableName);
    		} else {
    			keyspace = new TableKeyspace(conf, tableName);
    		}
    	} else if (snapshotName != null && restorePath != null) {
            keyspace = new SnapshotKeyspace(conf, snapshotName, restorePath);
        } else {
        	throw new IllegalArgumentException("Inconsistent arguments");
        }
        return keyspace;
    }

	/**
	 * Calculates the split keys (one for each permutation of the CSPO HBase Key prefix).
	 * 
	 * @param splitBits must be between 0 and 15, larger values result in more keys.
	 * @return An array of keys represented as {@code byte[]}s
	 */
	static byte[][] calculateSplits(final int splitBits, boolean quads, StatementIndices indices) {
		return calculateSplits(splitBits, quads, null, indices);
	}
	static byte[][] calculateSplits(final int splitBits, boolean quads, Map<IRI,Float> predicateRatios, StatementIndices indices) {
        StatementIndex<SPOC.S,SPOC.P,SPOC.O,SPOC.C> spo = indices.getSPOIndex();
        StatementIndex<SPOC.P,SPOC.O,SPOC.S,SPOC.C> pos = indices.getPOSIndex();
        StatementIndex<SPOC.O,SPOC.S,SPOC.P,SPOC.C> osp = indices.getOSPIndex();
        StatementIndex<SPOC.C,SPOC.S,SPOC.P,SPOC.O> cspo = indices.getCSPOIndex();
        StatementIndex<SPOC.C,SPOC.P,SPOC.O,SPOC.S> cpos = indices.getCPOSIndex();
        StatementIndex<SPOC.C,SPOC.O,SPOC.S,SPOC.P> cosp = indices.getCOSPIndex();
        RDFFactory rdfFactory = indices.getRDFFactory();
        TreeSet<byte[]> splitKeys = new TreeSet<>(Bytes.BYTES_COMPARATOR);
        //basic presplits
        splitKeys.add(new byte[]{ pos.prefix });
        splitKeys.add(new byte[]{ osp.prefix });
		if (quads) {
			splitKeys.add(new byte[] { cspo.prefix });
			splitKeys.add(new byte[] { cpos.prefix });
			splitKeys.add(new byte[] { cosp.prefix });
		}
        //common presplits
		addSplits(splitKeys, spo.prefix, splitBits, null, indices);
		addSplits(splitKeys, pos.prefix, splitBits, transformKeys(predicateRatios, iri -> rdfFactory.createPredicate(iri)), indices);
        addSplits(splitKeys, osp.prefix, splitBits, null, indices);
        if (quads) {
			addSplits(splitKeys, cspo.prefix, splitBits/2, null, indices);
			addSplits(splitKeys, cpos.prefix, splitBits/2, null, indices);
			addSplits(splitKeys, cosp.prefix, splitBits/2, null, indices);
        }
        return splitKeys.toArray(new byte[splitKeys.size()][]);
    }

	private static <K1,K2,V> Map<K2,V> transformKeys(Map<K1,V> map, Function<K1,K2> f) {
		if (map == null) {
			return null;
		}
		Map<K2,V> newMap = new HashMap<>(map.size()+1);
		for (Map.Entry<K1,V> entry : map.entrySet()) {
			newMap.put(f.apply(entry.getKey()), entry.getValue());
		}
		return newMap;
	}

	/**
	 * Generate the split keys and add it to the collection.
	 * 
	 * @param splitKeys the {@code TreeSet} to add the collection to.
	 * @param prefix the prefix to calculate the key for
	 * @param splitBits between 0 and 15, larger values generate smaller split steps
	 * @param rdfFactory RDFFactory
	 */
	private static void addSplits(TreeSet<byte[]> splitKeys, byte prefix, final int splitBits, Map<? extends RDFIdentifier<?>,Float> keyFractions, StatementIndices indices) {
        if (splitBits == 0) return;
		if (splitBits < 0 || splitBits > 15) {
			throw new IllegalArgumentException("Illegal nunmber of split bits");
		}

		int actualSplitBits = 0;
		int nonZeroSplitCount = 0;
		float fractionSum = 0.0f;
		if (keyFractions != null && !keyFractions.isEmpty()) {
			for (Float f : keyFractions.values()) {
				actualSplitBits += (int)Math.round(f*splitBits);
				if (actualSplitBits > 0) {
					nonZeroSplitCount++;
				}
				fractionSum += f;
			}
		}
		int otherSplitBits = (int)Math.round((1.0f - fractionSum)*splitBits);
		actualSplitBits += otherSplitBits;
		if (otherSplitBits > 0) {
			nonZeroSplitCount++;
		}
		float scale = (float)splitBits/(float)actualSplitBits;

		fractionSum = 0.0f;
		if (keyFractions != null && !keyFractions.isEmpty()) {
			ValueIdentifier.Format idFormat = indices.getRDFFactory().idFormat;
			for (Map.Entry<? extends RDFIdentifier<?>, Float> entry : keyFractions.entrySet()) {
				StatementIndex<?,?,?,?> index = indices.toIndex(prefix);
				RDFIdentifier<?> id = entry.getKey();
				byte[] keyHash = index.getRole(id.getRoleName()).keyHash(id.getId(), idFormat);
				byte[] keyPrefix = new byte[1+keyHash.length];
				keyPrefix[0] = prefix;
				System.arraycopy(keyHash, 0, keyPrefix, 1, keyHash.length);
				if (nonZeroSplitCount > 1) {
					// add divider
					splitKeys.add(keyPrefix);
				}
				float fraction = entry.getValue();
				int keySplitBits = (int)(scale*Math.round(fraction*splitBits));
				splitKey(splitKeys, keyPrefix, keySplitBits);
				fractionSum += fraction;
			}
		}

		otherSplitBits *= scale;
		splitKey(splitKeys, new byte[] {prefix}, otherSplitBits);
    }

	private static void splitKey(TreeSet<byte[]> splitKeys, byte[] prefix, final int splitBits) {
		final int splitStep = 1 << (16 - splitBits);
		for (int i = splitStep; i <= 0xFFFF; i += splitStep) {
            byte bb[] = Arrays.copyOf(prefix, prefix.length + 2);
            // write unsigned short
			bb[prefix.length] = (byte) ((i >> 8) & 0xFF);
            bb[prefix.length + 1] = (byte) (i & 0xFF);
            splitKeys.add(bb);
		}
	}

    /**
	 * Timestamp is shifted one bit left and the last bit is used to prioritize
	 * between inserts and deletes of the same time to avoid HBase ambiguity.
	 * Inserts are always considered later after deletes on a timeline.
	 * @param ts timestamp
	 * @param insert true if timestamp of an 'insert'
	 * @return Halyard internal timestamp value
	 */
	public static long toHalyardTimestamp(long ts, boolean insert) {
		// use arithmetic operations instead of bit-twiddling to correctly handle
		// negative timestamps
		long hts = 2 * ts;
		if (insert) {
			hts += 1;
		}
		return hts;
	}

	public static long fromHalyardTimestamp(long hts) {
		return hts >> 1; // NB: preserve sign
	}


	static Scan scanFirst(Scan scanAll) {
		scanAll.setCaching(1).setCacheBlocks(true).setOneRowLimit();
		appendFilter(scanAll, new FirstKeyOnlyFilter());
		return scanAll;
	}

	public static Scan scanCompleteRows(Scan scan) {
		return scan.setAllowPartialResults(false).setBatch(-1);
	}

	public static boolean exists(KeyspaceConnection kc, Scan scan) throws IOException {
		Scan existsScan = appendFilter(scanFirst(scan), new KeyOnlyFilter());
		try (ResultScanner scanner = kc.getScanner(existsScan)) {
			for (Result result : scanner) {
				if(!result.isEmpty()) {
					return true;
				}
			}
		}
		return false;
	}

	private static Scan appendFilter(Scan scan, Filter newFilter) {
		Filter existingFilter = scan.getFilter();
		if (existingFilter != null) {
			FilterList fl;
			if (existingFilter instanceof FilterList) {
				fl = (FilterList) existingFilter;
				fl.addFilter(newFilter);
			} else {
				fl = new FilterList(existingFilter, newFilter);
			}
			scan.setFilter(fl);
		} else {
			scan.setFilter(newFilter);
		}
		return scan;
	}

	/**
     * Helper method constructing a custom HBase Scan from given arguments
     * @param startRow start row key byte array (inclusive)
     * @param stopRow stop row key byte array (exclusive)
     * @param rowBatchSize number of rows to fetch per RPC
     * @param indiscriminate if the scan is indiscriminate (e.g. full table scan)
     * @return HBase Scan instance
     */
	static Scan scan(byte[] startRow, byte[] stopRow, int rowBatchSize, boolean indiscriminate) {
        Scan scan = new Scan();
        scan.addFamily(ColumnFamilyConfig.CF_NAME);
		scan.readVersions(READ_VERSIONS);
        scan.setAllowPartialResults(true);
        scan.setCaching(rowBatchSize);
        // dont cause the block cache to be flushed when doing an indiscriminate scan
        scan.setCacheBlocks(!indiscriminate);
        if(startRow != null) {
			scan.withStartRow(startRow);
        }
        if(stopRow != null) {
			scan.withStopRow(stopRow);
        }
        return scan;
    }

	static int rowBatchSize(int cardinality, int maxCachingLimit) {
		return Math.min(cardinality, maxCachingLimit);
	}

	public static byte[] getTableNameSuffixedWithFamily(byte[] tableName) {
		return Bytes.add(tableName, Bytes.toBytes(";"), ColumnFamilyConfig.CF_NAME);
	}
}
