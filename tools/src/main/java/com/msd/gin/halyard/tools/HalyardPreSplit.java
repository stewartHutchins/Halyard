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
package com.msd.gin.halyard.tools;

import static com.msd.gin.halyard.tools.HalyardBulkLoad.*;

import com.msd.gin.halyard.common.HalyardTableUtils;
import com.msd.gin.halyard.common.RDFFactory;
import com.msd.gin.halyard.common.StatementIndices;
import com.msd.gin.halyard.tools.HalyardBulkLoad.RioFileInputFormat;
import com.msd.gin.halyard.util.LRUCache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import org.apache.commons.cli.CommandLine;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.NullOutputFormat;
import org.eclipse.rdf4j.model.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Apache Hadoop MapReduce Tool for calculating pre-splits of an HBase table before a large dataset bulk-load.
 * Splits are based on the keys of a sample of the data to be loaded.
 * @author Adam Sotona (MSD)
 */
public final class HalyardPreSplit extends AbstractHalyardTool {
    private static final Logger LOG = LoggerFactory.getLogger(HalyardPreSplit.class);

    private static final String TOOL_NAME = "presplit";
    private static final String TABLE_PROPERTY = confProperty(TOOL_NAME, "table");
    private static final String SPLIT_LIMIT_PROPERTY = confProperty(TOOL_NAME, "limit");
    private static final String DECIMATION_FACTOR_PROPERTY = confProperty(TOOL_NAME, "decimation-factor");
    private static final String OVERWRITE_PROPERTY = confProperty(TOOL_NAME, "overwrite");

    private static final long DEFAULT_SPLIT_LIMIT = 55000000000l;
    private static final int DEFAULT_DECIMATION_FACTOR = 1000;
    private static final String NULL_TABLE = "-";

    enum Counters {
    	SAMPLED_STATEMENTS,
    	TOTAL_STATEMENTS,
    	TOTAL_STATEMENTS_READ,
    	TOTAL_SPLITS
    }

    /**
     * Mapper class transforming randomly selected sample of parsed Statement into set of HBase Keys and sizes
     */
    public final static class RDFDecimatingMapper extends Mapper<LongWritable, Statement, ImmutableBytesWritable, LongWritable> {

        private final ImmutableBytesWritable rowKey = new ImmutableBytesWritable();
        private final LongWritable keyValueLength = new LongWritable();
        private final SplittableRandom random = new SplittableRandom();
        private Set<Statement> stmtDedup;
        private StatementIndices stmtIndices;
        private int decimationFactor;
        private long sampledStmts = 0L;
        private long totalStmts = 0L;
        private long totalStmtsRead = 0L;
        private long nextStmtToSample = 0L;

        @Override
        protected void setup(Context context) throws IOException, InterruptedException {
            Configuration conf = context.getConfiguration();
            RDFFactory rdfFactory = RDFFactory.create(conf);
            stmtDedup = Collections.newSetFromMap(new LRUCache<>(conf.getInt(STATEMENT_DEDUP_CACHE_SIZE_PROPERTY, DEFAULT_STATEMENT_DEDUP_CACHE_SIZE)));
            stmtIndices = new StatementIndices(conf, rdfFactory);
            decimationFactor = conf.getInt(DECIMATION_FACTOR_PROPERTY, DEFAULT_DECIMATION_FACTOR);
        }

        @Override
        protected void map(LongWritable key, Statement stmt, final Context context) throws IOException, InterruptedException {
        	// best effort statement deduplication
        	if (stmtDedup.add(stmt)) {
	        	if (totalStmts % decimationFactor == 0) {
	        		// pick a random representative from the next decimationFactor worth of statements
	       			nextStmtToSample = totalStmts + random.nextInt(decimationFactor);
	        	}
	        	if (totalStmts == nextStmtToSample) {
	                List<? extends KeyValue> kvs = stmtIndices.insertKeyValues(stmt.getSubject(), stmt.getPredicate(), stmt.getObject(), stmt.getContext(), 0);
	        		for (KeyValue keyValue: kvs) {
	                    rowKey.set(keyValue.getRowArray(), keyValue.getRowOffset(), keyValue.getRowLength());
	                    keyValueLength.set(keyValue.getLength());
	                    context.write(rowKey, keyValueLength);
	                }
	        		sampledStmts++;
	            }
	            totalStmts++;
        	}
        	totalStmtsRead++;
        }

        @Override
        protected void cleanup(Context context) throws IOException, InterruptedException {
        	context.getCounter(Counters.SAMPLED_STATEMENTS).increment(sampledStmts);
        	context.getCounter(Counters.TOTAL_STATEMENTS).increment(totalStmts);
        	context.getCounter(Counters.TOTAL_STATEMENTS_READ).increment(totalStmtsRead);
        }
    }

    static final class PreSplitReducer extends Reducer<ImmutableBytesWritable, LongWritable, NullWritable, NullWritable>  {
    	private static final Logger logger = LoggerFactory.getLogger(PreSplitReducer.class);
        private final List<byte[]> splits = new ArrayList<>();
        private long splitLimit;
        private int decimationFactor;
        private byte lastRegion = 0;
        private int keyCount = 0;
        private int valueCount = 0;
        private long valueSize = 0;
        private int maxValueCount = 0;
        private long maxValueSize = 0;
        private byte[] maxCountKey;
        private byte[] maxSizeKey;

        @Override
        protected void setup(Context context) throws IOException, InterruptedException {
            splitLimit = context.getConfiguration().getLong(SPLIT_LIMIT_PROPERTY, DEFAULT_SPLIT_LIMIT);
            decimationFactor = context.getConfiguration().getInt(DECIMATION_FACTOR_PROPERTY, DEFAULT_DECIMATION_FACTOR);
            logger.info("NB: results may be affected by duplicate statements");
        }

        @Override
        public void reduce(ImmutableBytesWritable key, Iterable<LongWritable> values, Context context) throws IOException, InterruptedException {
            final byte region = key.get()[key.getOffset()];
            boolean isNewRegion = (lastRegion != region);
            final long splitSize = valueSize * decimationFactor;
            if (isNewRegion || splitSize > splitLimit) {
                byte[] splitBytes = isNewRegion ? new byte[]{region} : key.copyBytes();
                splits.add(splitBytes);
                int splitNum = splits.size();
                String splitString = Bytes.toHex(splitBytes);
                context.setStatus("#" + splitNum + " " + splitString);
                logger.info("Split {} ({}): size {}, sampled keys {}, mean value count {}, mean value size {}, max value count {} ({}), max value size {} ({})", splitNum, splitString, splitSize, keyCount, valueCount/keyCount, valueSize/keyCount, maxValueCount, Bytes.toHex(maxCountKey), maxValueSize, Bytes.toHex(maxSizeKey));
                lastRegion = region;
                keyCount = 0;
                valueCount = 0;
                valueSize = 0;
                maxValueCount = 0;
                maxValueSize = 0;
                maxCountKey = null;
                maxSizeKey = null;
            }
            int sampleCount = 0;
            int sampledSize = 0;
            for (LongWritable val : values) {
            	sampleCount++;
            	sampledSize += val.get();
            }
            keyCount++;
            valueCount += sampleCount;
            valueSize += sampledSize;
            if (sampleCount > maxValueCount) {
            	maxValueCount = sampleCount;
            	maxCountKey = key.copyBytes();
            }
            if (sampledSize > maxValueSize) {
            	maxValueSize = sampledSize;
            	maxSizeKey = key.copyBytes();
            }
        }

        @Override
        protected void cleanup(Context context) throws IOException, InterruptedException {
        	context.getCounter(Counters.TOTAL_SPLITS).setValue(splits.size());
            Configuration conf = context.getConfiguration();
            String target = conf.get(TABLE_PROPERTY);
            if (!NULL_TABLE.equals(target)) {
	            TableName tableName = TableName.valueOf(target);
	            boolean overwrite = conf.getBoolean(OVERWRITE_PROPERTY, false);
	            try (Connection conn = HalyardTableUtils.getConnection(conf)) {
	            	if (overwrite) {
			    		try (Admin admin = conn.getAdmin()) {
			    			if (admin.tableExists(tableName)) {
			    				admin.disableTable(tableName);
			    				admin.deleteTable(tableName);
			    			}
			    		}
	            	}
		            HalyardTableUtils.createTable(conn, tableName, splits.toArray(new byte[splits.size()][])).close();
	            }
            }
        }
    }

    public HalyardPreSplit() {
        super(
            TOOL_NAME,
            "Halyard Presplit is a MapReduce application designed to estimate optimal HBase region splits for big datasets before the Bulk Load. "
                + "Halyard PreSplit creates an empty HBase table based on calculations from the dataset sources sampling. "
                + "For very large datasets it is wise to calculate the pre-splits before the HBase table is created to allow more efficient following Bulk Load process of the data. "
                + "Optional definition or override of the named graph should be specified exactly the same as for the following Bulk Load process so the region presplits estimations are precise.\n"
                + "Halyard PreSplit consumes the same RDF data sources as Halyard Bulk Load.",
            "Example: halyard presplit -s hdfs://my_RDF_files -t mydataset [-g 'http://whatever/graph']"
        );
        addOption("s", "source", "source_paths", SOURCE_PATHS_PROPERTY, "Source path(s) with RDF files, more paths can be delimited by comma, the paths are recursively searched for the supported files", true, true);
        addOption("t", "target", "dataset_table", TABLE_PROPERTY, "Target HBase table with Halyard RDF store, optional HBase namespace of the target table must already exist", true, true);
        addOption("i", "allow-invalid-iris", null, ALLOW_INVALID_IRIS_PROPERTY, "Optionally allow invalid IRI values (less overhead)", false, false);
        addOption("k", "skip-invalid-lines", null, SKIP_INVALID_LINES_PROPERTY, "Optionally skip invalid lines", false, false);
        addOption("g", "default-named-graph", "named_graph", DEFAULT_CONTEXT_PROPERTY, "Optionally specify default target named graph", false, true);
        addOption("o", "named-graph-override", null, OVERRIDE_CONTEXT_PROPERTY, "Optionally override named graph also for quads, named graph is stripped from quads if --default-named-graph option is not specified", false, false);
        addOption("d", "decimation-factor", "decimation_factor", DECIMATION_FACTOR_PROPERTY, String.format("Optionally overide pre-split random decimation factor (default is %d)", DEFAULT_DECIMATION_FACTOR), false, true);
        addOption("l", "split-limit-splitSize", "splitSize", SPLIT_LIMIT_PROPERTY, String.format("Optionally override calculated split splitSize (default is %d)", DEFAULT_SPLIT_LIMIT), false, true);
        addOption("f", "force", null, OVERWRITE_PROPERTY, "Overwrite existing table", false, false);
    }

    @Override
    protected int run(CommandLine cmd) throws Exception {
    	configureString(cmd, 's', null);
    	configureString(cmd, 't', null);
        configureBoolean(cmd, 'f');
        String target = getConf().get(TABLE_PROPERTY);
        if (!NULL_TABLE.equals(target) && !getConf().getBoolean(OVERWRITE_PROPERTY, false)) {
	        try (Connection con = ConnectionFactory.createConnection(getConf())) {
	            try (Admin admin = con.getAdmin()) {
	                if (admin.tableExists(TableName.valueOf(target))) {
	                    LOG.warn("Pre-split cannot modify already existing table {}", target);
	                    return -1;
	                }
	            }
	        }
        }
        configureBoolean(cmd, 'i');
        configureBoolean(cmd, 'k');
        configureIRIPattern(cmd, 'g', null);
        configureBoolean(cmd, 'o');
        configureInt(cmd, 'd', DEFAULT_DECIMATION_FACTOR, v -> {
            if (v <= 0) {
            	throw new IllegalArgumentException("Decimation factor must be greater than zero");
            }
        });
        configureLong(cmd, 'l', DEFAULT_SPLIT_LIMIT);
        String sourcePaths = getConf().get(SOURCE_PATHS_PROPERTY);
        addRioDependencies(getConf());
        HBaseConfiguration.addHbaseResources(getConf());
        Job job = Job.getInstance(getConf(), "HalyardPreSplit -> " + target);
        job.setJarByClass(HalyardPreSplit.class);
        job.setMapperClass(RDFDecimatingMapper.class);
        job.setMapOutputKeyClass(ImmutableBytesWritable.class);
        job.setMapOutputValueClass(LongWritable.class);
        job.setInputFormatClass(RioFileInputFormat.class);
        FileInputFormat.setInputDirRecursive(job, true);
        FileInputFormat.setInputPaths(job, sourcePaths);
        TableMapReduceUtil.addDependencyJars(job);
        TableMapReduceUtil.initCredentials(job);
        job.setReducerClass(PreSplitReducer.class);
        job.setNumReduceTasks(1);
        job.setOutputFormatClass(NullOutputFormat.class);
        if (job.waitForCompletion(true)) {
            LOG.info("PreSplit Calculation completed.");
            return 0;
        } else {
    		LOG.error("PreSplit failed to complete.");
            return -1;
        }
    }
}
