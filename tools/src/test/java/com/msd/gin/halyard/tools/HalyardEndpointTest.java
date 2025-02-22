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

import com.msd.gin.halyard.common.HBaseServerTestInstance;
import com.msd.gin.halyard.sail.HBaseSail;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.apache.commons.cli.MissingOptionException;
import org.apache.commons.cli.ParseException;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.util.ToolRunner;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import static org.junit.Assert.*;

/**
 * Class for testing the tool HalyardEndpoint.
 *
 * @author sykorjan
 */
public class HalyardEndpointTest {
    private static final String TABLE = "endpointTestTable";
    private static String ROOT;

    @Rule
    public TestName name = new TestName();

    /**
     * Create temporary testing folder for testing files, create Sail repository and add testing data
     */
    @BeforeClass
    public static void setup() throws Exception {
        File rf = AbstractHalyardToolTest.createTempDir("HalyardEndpointTest");
        ROOT = rf.getPath();
        if (!ROOT.endsWith("/")) {
            ROOT = ROOT + "/";
        }
        ValueFactory vf = SimpleValueFactory.getInstance();
        HBaseSail sail = new HBaseSail(HBaseServerTestInstance.getInstanceConfig(), TABLE, true, 0, true, 0, null, null);
        sail.init();
		try (SailConnection conn = sail.getConnection()) {
			for (int i = 0; i < 10; i++) {
				for (int j = 0; j < 10; j++) {
					for (int k = 0; k < 10; k++) {
						conn.addStatement(vf.createIRI("http://whatever/subj" + i), vf.createIRI("http://whatever/pred" + j), vf.createLiteral("whatever\n\"\\" + k));
					}
				}
			}
		}
        sail.shutDown();
    }

    /**
     * Delete the temporary testing folder
     */
    @AfterClass
    public static void teardown() throws Exception {
        FileUtils.deleteDirectory(new File(ROOT));
    }

    @Test(expected = ParseException.class)
    public void testMissingArgs() throws Exception {
        runEndpoint("-p", "8081");
    }

    @Test(expected = ParseException.class)
    public void testUnknownArg() throws Exception {
        runEndpoint("-y");
    }

    @Test(expected = ParseException.class)
    public void testDupArgs() throws Exception {
        runEndpoint("-s", "whatever", "-p", "8081", "-s", "whatever2");
    }

    @Test(expected = HalyardEndpoint.EndpointException.class)
    public void testInvalidTimeout() throws Exception {
        runEndpoint("-s", "whatever", "-p", "8081", "-t", "1234abc");
    }

    @Test(expected = HalyardEndpoint.EndpointException.class)
    public void testInvalidPort() throws Exception {
        runEndpoint("-s", "whatever", "-p", "abc8081");
    }

    /**
     * Missing required option '-s' due to stopping parsing after unrecognized option 'script'
     * (User's custom arguments have to be after all tool options)
     */
    @Test(expected = MissingOptionException.class)
    public void testCustomArgumentsNotLast() throws Exception {
        runEndpoint("script arg1 arg2 arg3", "-s", TABLE);
    }

    /**
     * Cannot execute subprocess (non-existing command "tmp.tmp arg1 arg2 arg3")
     */
    @Test(expected = HalyardEndpoint.EndpointException.class)
    public void testRunMissingScript() throws Exception {
        File tmp = File.createTempFile("tmp", "tmp");
        tmp.delete();
        runEndpoint("-s", TABLE, "-p", "8081", tmp.getPath() + " arg1 arg2 arg3");
    }

    /**
     * Positive test - HalyardEndpoint is run with valid arguments. Checking for positive subprocess output.
     */
    @Test
    public void testSelect() throws Exception {
        File script = new File(this.getClass().getResource("testScript-select.sh").getPath());
        script.setExecutable(true);
        Path path = Paths.get(ROOT + name.getMethodName());
        runEndpoint("-s", TABLE, script.getPath(), path.toString());
        assertTrue(Files.exists(path));
        assertTrue(Files.lines(path).count() >= 10);
    }

    @Test
    public void testUpdate() throws Exception {
        File script = new File(this.getClass().getResource("testScript-update.sh").getPath());
        script.setExecutable(true);
        Path path = Paths.get(ROOT + name.getMethodName());
        runEndpoint("-s", TABLE, script.getPath(), path.toString());
        assertTrue(Files.exists(path));
        assertEquals("true", Files.readString(path));
    }

    @Test
    public void testMapReduceUpdate() throws Exception {
        File script = new File(this.getClass().getResource("testScript-update-mapreduce.sh").getPath());
        script.setExecutable(true);
        Path path = Paths.get(ROOT + name.getMethodName());
        runEndpoint("-s", TABLE, script.getPath(), path.toString());
        assertTrue(Files.exists(path));
        String contents = Files.readString(path);
        assertTrue(contents, contents.startsWith("{\"jobs\":[{"));
    }

    @Test
    public void testMapReduceStats() throws Exception {
        File script = new File(this.getClass().getResource("testScript-stats-mapreduce.sh").getPath());
        script.setExecutable(true);
        Path path = Paths.get(ROOT + name.getMethodName());
        runEndpoint("-s", TABLE, script.getPath(), path.toString());
        assertTrue(Files.exists(path));
        String contents = Files.readString(path);
        assertEquals("", contents);
    }

    @Test
    public void testStoredQueries() throws Exception {
        File queries = new File(this.getClass().getResource("test.properties").getPath());
        File script = new File(this.getClass().getResource("testScript-storedQueries.sh").getPath());
        script.setExecutable(true);
        Path path = Paths.get(ROOT + name.getMethodName());
        runEndpoint("-s", TABLE, "-q", queries.getPath(), script.getPath(), path.toString());
        assertTrue(Files.exists(path));
        List<String> lines = Files.readAllLines(path);
        // 42 instead of 22 as naively splitting on any newline instead of proper CSV parser handling of quoted newlines
        assertEquals("Content was:\n" + String.join("\n", lines), 42, lines.size());
        assertEquals("Content was:\n" + String.join("\n", lines), "s,p,o", lines.get(0));
        assertEquals("Content was:\n" + String.join("\n", lines), "s,p,o", lines.get(21));
    }

    @Test(expected = RuntimeException.class)
    public void testStoredQueriesMissingFile() throws Exception {
        File queries = new File(this.getClass().getResource("testFail.properties").getPath());
        runEndpoint("-s", TABLE, "-q", queries.getPath());
    }

    @Test
    public void testResultTracking() throws Exception {
        File script = new File(this.getClass().getResource("testScript-update-results.sh").getPath());
        script.setExecutable(true);
        Path path = Paths.get(ROOT + name.getMethodName());
        runEndpoint("-s", TABLE, script.getPath(), path.toString());
        assertTrue(Files.exists(path));
        assertEquals("{\"results\":[{\"totalInserted\":1001,\"totalDeleted\":0}]}", Files.readString(path));
    }

    /**
     * Run HalyardEndpoint tool with provided arguments via Hadoop ToolRunner
     *
     * @param args provided command line arguments
     * @return Exit code of the run method
     */
    private int runEndpoint(String... args) throws Exception {
        return ToolRunner.run(HBaseServerTestInstance.getInstanceConfig(), new HalyardEndpoint(), args);
    }
}
