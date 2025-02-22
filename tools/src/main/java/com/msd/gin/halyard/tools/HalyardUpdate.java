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

import com.msd.gin.halyard.repository.HBaseRepository;
import com.msd.gin.halyard.sail.ElasticSettings;
import com.msd.gin.halyard.sail.HBaseSail;

import org.apache.commons.cli.CommandLine;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.Update;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command line tool executing SPARQL Update query on Halyard dataset directly
 * @author Adam Sotona (MSD)
 */
public final class HalyardUpdate extends AbstractHalyardTool {
    private static final Logger LOG = LoggerFactory.getLogger(HalyardUpdate.class);

    public HalyardUpdate() {
        super(
            "update",
            "Halyard Update is a command-line application designed to run SPARQL Update operations to transform data in an HBase Halyard dataset",
            "Example: halyard update -s my_dataset -q 'insert {?o owl:sameAs ?s} where {?s owl:sameAs ?o}'"
        );
        addOption("s", "source-dataset", "dataset_table", "Source HBase table with Halyard RDF store", true, true);
        addOption("q", "update-operation", "sparql_update_operation", "SPARQL update operation to be executed", true, true);
        addOption("i", "elastic-index", "elastic_index_url", ElasticSettings.ELASTIC_INDEX_URL, "Optional ElasticSearch index URL", false, true);
    }


    public int run(CommandLine cmd) throws Exception {
        String source = cmd.getOptionValue('s');
        String query = cmd.getOptionValue('q');
        configureString(cmd, 'i', null);
        HBaseRepository rep = new HBaseRepository(new HBaseSail(getConf(), source, false, 0, true, 0, ElasticSettings.from(getConf()), null));
        rep.init();
        try {
        	try(RepositoryConnection conn = rep.getConnection()) {
	            Update u = conn.prepareUpdate(QueryLanguage.SPARQL, query);
	            LOG.info("Update execution started");
	            u.execute();
	            LOG.info("Update finished");
        	}
        } finally {
            rep.shutDown();
        }
        return 0;
    }
}
