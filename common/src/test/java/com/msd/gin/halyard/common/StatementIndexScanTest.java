package com.msd.gin.halyard.common;

import com.msd.gin.halyard.model.LiteralConstraint;
import com.msd.gin.halyard.model.ValueConstraint;
import com.msd.gin.halyard.model.ValueType;
import com.msd.gin.halyard.model.vocabulary.HALYARD;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Triple;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;

@RunsLocalHBase
public class StatementIndexScanTest {
    private static final String SUBJ = "http://whatever/subj";
    private static final String CTX = "http://whatever/ctx";

    private static final ValueFactory vf = SimpleValueFactory.getInstance();
    private static Connection hConn;
	private static KeyspaceConnection keyspaceConn;
	private static RDFFactory rdfFactory;
	private static StatementIndices stmtIndices;
    private static Set<Statement> allStatements;
    private static Set<Statement> defaultGraphStatements;
    private static Set<Statement> namedGraphStatements;
    private static Set<Literal> allLiterals;
    private static Set<Literal> stringLiterals;
    private static Set<Literal> nonstringLiterals;
    private static Set<Triple> allTriples;
    private static final Literal foobarLiteral = vf.createLiteral("foobar", vf.createIRI("http://whatever/datatype"));

    @BeforeAll
    public static void setup() throws Exception {
		Configuration conf = HBaseServerTestInstance.getInstanceConfig();
		hConn = HalyardTableUtils.getConnection(conf);
        Table table = HalyardTableUtils.getTable(hConn, "testStatementIndex", true, 0);
        keyspaceConn = new TableKeyspace.TableKeyspaceConnection(table);
		rdfFactory = RDFFactory.create(keyspaceConn);
		stmtIndices = new StatementIndices(conf, rdfFactory);

        stringLiterals = new HashSet<>();
        nonstringLiterals = new HashSet<>();
        for (int i=0; i<10; i++) {
			stringLiterals.add(vf.createLiteral(String.valueOf(i + Math.random())));
			stringLiterals.add(vf.createLiteral(String.valueOf(i + Math.random()), "en"));
			nonstringLiterals.add(vf.createLiteral(i + Math.random()));
			nonstringLiterals.add(vf.createLiteral((long) (i + Math.random())));
        }
        nonstringLiterals.add(vf.createLiteral(new Date()));
        nonstringLiterals.add(foobarLiteral);
        allLiterals = new HashSet<>();
        allLiterals.addAll(stringLiterals);
        allLiterals.addAll(nonstringLiterals);
        Resource subj = vf.createIRI(SUBJ);
        Resource ctx = vf.createIRI(CTX);
        allStatements = new HashSet<>();
        namedGraphStatements = new HashSet<>();
        allTriples = new HashSet<>();
        for (Literal l : allLiterals) {
        	Statement stmt = vf.createStatement(subj, RDF.VALUE, l, ctx);
        	namedGraphStatements.add(stmt);
            allStatements.add(stmt);
            // add some non-literal objects
            stmt = vf.createStatement(subj, OWL.SAMEAS, vf.createBNode(), ctx);
        	namedGraphStatements.add(stmt);
            allStatements.add(stmt);
            // add some triples
            Triple t = vf.createTriple(subj, RDF.VALUE, l);
            allTriples.add(t);
            allStatements.add(vf.createStatement(subj, RDFS.SEEALSO, t));
        }
        defaultGraphStatements = new HashSet<>();
        defaultGraphStatements.addAll(allStatements);
        long timestamp = System.currentTimeMillis();
		List<Put> puts = new ArrayList<>();
		for (Statement stmt : allStatements) {
            for (Cell kv : stmtIndices.insertKeyValues(stmt.getSubject(), stmt.getPredicate(), stmt.getObject(), stmt.getContext(), timestamp)) {
				puts.add(new Put(kv.getRowArray(), kv.getRowOffset(), kv.getRowLength(), kv.getTimestamp()).add(kv));
            }
		}
		table.put(puts);
		// expect triple statements to be returned
        for (Triple t : allTriples) {
            allStatements.add(vf.createStatement(t.getSubject(), t.getPredicate(), t.getObject(), HALYARD.TRIPLE_GRAPH_CONTEXT));
        }
    }

    @AfterAll
    public static void teardown() throws Exception {
        keyspaceConn.close();
    }

    private static Statement[] parseStatements(Result r) {
        return parseStatements(null, null, null, null, r);
    }

    private static Statement[] parseStatements(RDFSubject s, RDFPredicate p, RDFObject o, RDFContext c, Result r) {
        return stmtIndices.parseStatements(s, p, o, c, r, vf);
    }

    @Test
    public void testScanAll() throws Exception {
        Scan scan = stmtIndices.scanAll();
        Set<Statement> actual = getStatements(scan);
        assertSets(allStatements, actual);
    }

    @Test
    public void testScanDefaultIndices() throws Exception {
        Scan scan = stmtIndices.scanDefaultIndices();
        Set<Statement> actual = getStatements(scan);
        assertSets(defaultGraphStatements, actual);
    }

    @Test
    public void testScanContextIndices() throws Exception {
        List<Scan> scans = stmtIndices.scanContextIndices(vf.createIRI(CTX));
        for (Scan scan : scans) {
            Set<Statement> actual = getStatements(scan);
	        assertSets(namedGraphStatements, actual);
        }
    }

    @Test
    public void testScanLiterals() throws Exception {
        Set<Literal> actual = new HashSet<>();
        Scan scan = stmtIndices.scanLiterals(null, null);
        try (ResultScanner rs = keyspaceConn.getScanner(scan)) {
            Result r;
            while ((r = rs.next()) != null) {
                for (Statement stmt : parseStatements(r)) {
                    assertTrue(stmt.getObject().isLiteral(), "Not a literal: "+stmt.getObject());
                    actual.add((Literal) stmt.getObject());
                }
            }
        }
        assertSets(allLiterals, actual);
    }

    @Test
    public void testScanLiteralsPredicate() throws Exception {
        Set<Literal> actual = new HashSet<>();
        Scan scan = stmtIndices.scanLiterals(RDF.VALUE, null);
        try (ResultScanner rs = keyspaceConn.getScanner(scan)) {
            Result r;
            while ((r = rs.next()) != null) {
                for (Statement stmt : parseStatements(r)) {
                    assertTrue(stmt.getObject().isLiteral(), "Not a literal: "+stmt.getObject());
                    actual.add((Literal) stmt.getObject());
                }
            }
        }
        assertSets(allLiterals, actual);
    }

    @Test
    public void testScanLiteralsContext() throws Exception {
        Set<Literal> actual = new HashSet<>();
        Scan scan = stmtIndices.scanLiterals(null, vf.createIRI(CTX));
        try (ResultScanner rs = keyspaceConn.getScanner(scan)) {
            Result r;
            while ((r = rs.next()) != null) {
                for (Statement stmt : parseStatements(r)) {
                    assertTrue(stmt.getObject().isLiteral(), "Not a literal: "+stmt.getObject());
                    actual.add((Literal) stmt.getObject());
                }
            }
        }
        assertSets(allLiterals, actual);
    }

    @Test
    public void testScanLiteralsPredicateContext() throws Exception {
        Set<Literal> actual = new HashSet<>();
        Scan scan = stmtIndices.scanLiterals(RDF.VALUE, vf.createIRI(CTX));
        try (ResultScanner rs = keyspaceConn.getScanner(scan)) {
            Result r;
            while ((r = rs.next()) != null) {
                for (Statement stmt : parseStatements(r)) {
                    assertTrue(stmt.getObject().isLiteral(), "Not a literal: "+stmt.getObject());
                    actual.add((Literal) stmt.getObject());
                }
            }
        }
        assertSets(allLiterals, actual);
    }

    @Test
    public void testAllTermScan() throws Exception {
    	for (Statement stmt : allStatements) {
    		Scan scan = stmtIndices.scanAny(
    			rdfFactory.createSubject(stmt.getSubject()),
    			rdfFactory.createPredicate(stmt.getPredicate()),
    			rdfFactory.createObject(stmt.getObject()),
    			rdfFactory.createContext(stmt.getContext())
    			);
            try (ResultScanner rs = keyspaceConn.getScanner(scan)) {
                Result r = rs.next();
                Statement[] actualStmts = parseStatements(r);
                assertArrayEquals(new Statement[] {stmt}, actualStmts);
            }
    	}
    }

    @Test
    public void testScanAll_SPO() throws Exception {
        List<Statement> actual = new ArrayList<>();
        Scan scan = stmtIndices.getSPOIndex().scan();
        try (ResultScanner rs = keyspaceConn.getScanner(scan)) {
            Result r;
            while ((r = rs.next()) != null) {
                for (Statement stmt : parseStatements(r)) {
                    actual.add(stmt);
                }
            }
        }
        assertNoDuplicates(defaultGraphStatements, actual);
    }

    @Test
    public void testScanStringLiterals_SPO() throws Exception {
        Resource subj = vf.createIRI(SUBJ);
        RDFSubject rdfSubj = rdfFactory.createSubject(subj);
        RDFPredicate rdfPred = rdfFactory.createPredicate(RDF.VALUE);

        Set<Literal> actual = new HashSet<>();
        Scan scan = stmtIndices.getSPOIndex().scanWithConstraint(rdfSubj, rdfPred, StatementIndices.NO_PARTITIONING, 0, new LiteralConstraint(XSD.STRING), null);
        try (ResultScanner rs = keyspaceConn.getScanner(scan)) {
            Result r;
            while ((r = rs.next()) != null) {
                for (Statement stmt : parseStatements(rdfSubj, rdfPred, null, null, r)) {
                    assertTrue(stmt.getObject().isLiteral(), "Not a literal: "+stmt.getObject());
                    actual.add((Literal) stmt.getObject());
                }
            }
        }
        assertSets(stringLiterals, actual);
    }

    @Test
    public void testScanStringLiteralPartitions_SPO() throws Exception {
        Resource subj = vf.createIRI(SUBJ);
        RDFSubject rdfSubj = rdfFactory.createSubject(subj);
        RDFPredicate rdfPred = rdfFactory.createPredicate(RDF.VALUE);

        int nbits = 3;
        int numPartitions = (1 << nbits);
        Set<Literal> actualTotal = new HashSet<>();
        for (int i=0; i<numPartitions; i++) {
            Set<Literal> actualPartition = new HashSet<>();
	        Scan scan = stmtIndices.getSPOIndex().scanWithConstraint(rdfSubj, rdfPred, i, nbits, new LiteralConstraint(XSD.STRING), null);
	        try (ResultScanner rs = keyspaceConn.getScanner(scan)) {
	            Result r;
	            while ((r = rs.next()) != null) {
	                for (Statement stmt : parseStatements(rdfSubj, rdfPred, null, null, r)) {
	                    assertTrue(stmt.getObject().isLiteral(), "Not a literal: "+stmt.getObject());
	                    actualPartition.add((Literal) stmt.getObject());
	                }
	            }
	        }
	        assertThat(actualPartition.size()).as("Partition %d", i).isLessThan(stringLiterals.size());
	        actualTotal.addAll(actualPartition);
        }
        assertSets(stringLiterals, actualTotal);
    }

    /**
     * Test scan is correct with a partitioned index and constraint if partitioning is turned off.
     * @throws Exception
     */
    @Test
    public void testScanStringLiteralPartitions_NoPartitioning_SPO() throws Exception {
        Resource subj = vf.createIRI(SUBJ);
        RDFSubject rdfSubj = rdfFactory.createSubject(subj);
        RDFPredicate rdfPred = rdfFactory.createPredicate(RDF.VALUE);

        int nbits = 3;
        Set<Literal> actualTotal = new HashSet<>();
        Scan scan = stmtIndices.getSPOIndex().scanWithConstraint(rdfSubj, rdfPred, StatementIndices.NO_PARTITIONING, nbits, new LiteralConstraint(XSD.STRING), null);
        try (ResultScanner rs = keyspaceConn.getScanner(scan)) {
            Result r;
            while ((r = rs.next()) != null) {
                for (Statement stmt : parseStatements(rdfSubj, rdfPred, null, null, r)) {
                    assertTrue(stmt.getObject().isLiteral(), "Not a literal: "+stmt.getObject());
                    actualTotal.add((Literal) stmt.getObject());
                }
            }
        }
        assertSets(stringLiterals, actualTotal);
    }

    @Test
    public void testScanNonStringLiterals_CSPO() throws Exception {
        Resource subj = vf.createIRI(SUBJ);
        Resource ctx = vf.createIRI(CTX);
        RDFSubject rdfSubj = rdfFactory.createSubject(subj);
        RDFPredicate rdfPred = rdfFactory.createPredicate(RDF.VALUE);
        RDFContext rdfCtx = rdfFactory.createContext(ctx);

        Set<Literal> actual = new HashSet<>();
        Scan scan = stmtIndices.getCSPOIndex().scanWithConstraint(rdfCtx, rdfSubj, rdfPred, StatementIndices.NO_PARTITIONING, 0, new LiteralConstraint(HALYARD.NON_STRING_TYPE));
        try (ResultScanner rs = keyspaceConn.getScanner(scan)) {
            Result r;
            while ((r = rs.next()) != null) {
                for (Statement stmt : parseStatements(rdfSubj, rdfPred, null, rdfCtx, r)) {
                    assertTrue(stmt.getObject().isLiteral(), "Not a literal: "+stmt.getObject());
                    actual.add((Literal) stmt.getObject());
                }
            }
        }
        assertSets(nonstringLiterals, actual);
    }

    @Test
    public void testScanNonStringLiteralPartitions_CSPO() throws Exception {
        Resource subj = vf.createIRI(SUBJ);
        Resource ctx = vf.createIRI(CTX);
        RDFSubject rdfSubj = rdfFactory.createSubject(subj);
        RDFPredicate rdfPred = rdfFactory.createPredicate(RDF.VALUE);
        RDFContext rdfCtx = rdfFactory.createContext(ctx);

        int nbits = 3;
        int numPartitions = (1 << nbits);
        Set<Literal> actualTotal = new HashSet<>();
        for (int i=0; i<numPartitions; i++) {
            Set<Literal> actualPartition = new HashSet<>();
	        Scan scan = stmtIndices.getCSPOIndex().scanWithConstraint(rdfCtx, rdfSubj, rdfPred, i, nbits, new LiteralConstraint(HALYARD.NON_STRING_TYPE));
	        try (ResultScanner rs = keyspaceConn.getScanner(scan)) {
	            Result r;
	            while ((r = rs.next()) != null) {
	                for (Statement stmt : parseStatements(rdfSubj, rdfPred, null, rdfCtx, r)) {
	                    assertTrue(stmt.getObject().isLiteral(), "Not a literal: "+stmt.getObject());
	                    actualPartition.add((Literal) stmt.getObject());
	                }
	            }
	        }
	        assertThat(actualPartition.size()).as("Partition %d", i).isLessThan(nonstringLiterals.size());
	        actualTotal.addAll(actualPartition);
        }
        assertSets(nonstringLiterals, actualTotal);
    }

    @Test
    public void testScanTriples_SPO() throws Exception {
        Resource subj = vf.createIRI(SUBJ);
        RDFSubject rdfSubj = rdfFactory.createSubject(subj);
        RDFPredicate rdfPred = rdfFactory.createPredicate(RDFS.SEEALSO);

        Set<Triple> actual = new HashSet<>();
        Scan scan = stmtIndices.getSPOIndex().scanWithConstraint(rdfSubj, rdfPred, StatementIndices.NO_PARTITIONING, 0, new ValueConstraint(ValueType.TRIPLE), null);
        try (ResultScanner rs = keyspaceConn.getScanner(scan)) {
            Result r;
            while ((r = rs.next()) != null) {
                for (Statement stmt : parseStatements(rdfSubj, rdfPred, null, null, r)) {
                    assertTrue(stmt.getObject().isTriple(), "Not a triple: "+stmt.getObject());
                    actual.add((Triple) stmt.getObject());
                }
            }
        }
        assertSets(allTriples, actual);
    }

    @Test
    public void testScanStringLiterals_POS() throws Exception {
        RDFPredicate rdfPred = rdfFactory.createPredicate(RDF.VALUE);

        Set<Literal> actual = new HashSet<>();
        Scan scan = stmtIndices.getPOSIndex().scanWithConstraint(rdfPred, StatementIndices.NO_PARTITIONING, 0, new LiteralConstraint(XSD.STRING), null, null);
        try (ResultScanner rs = keyspaceConn.getScanner(scan)) {
            Result r;
            while ((r = rs.next()) != null) {
                for (Statement stmt : parseStatements(null, rdfPred, null, null, r)) {
                    assertTrue(stmt.getObject().isLiteral(), "Not a literal: "+stmt.getObject());
                    actual.add((Literal) stmt.getObject());
                }
            }
        }
        assertSets(stringLiterals, actual);
    }

    @Test
    public void testScanStringLiteralPartitions_POS() throws Exception {
        RDFPredicate rdfPred = rdfFactory.createPredicate(RDF.VALUE);

        int nbits = 3;
        int numPartitions = (1 << nbits);
        Set<Literal> actualTotal = new HashSet<>();
        for (int i=0; i<numPartitions; i++) {
            Set<Literal> actualPartition = new HashSet<>();
	        Scan scan = stmtIndices.getPOSIndex().scanWithConstraint(rdfPred, i, nbits, new LiteralConstraint(XSD.STRING), null, null);
	        try (ResultScanner rs = keyspaceConn.getScanner(scan)) {
	            Result r;
	            while ((r = rs.next()) != null) {
	                for (Statement stmt : parseStatements(null, rdfPred, null, null, r)) {
	                    assertTrue(stmt.getObject().isLiteral(), "Not a literal: "+stmt.getObject());
	                    actualPartition.add((Literal) stmt.getObject());
	                }
	            }
	        }
	        assertThat(actualPartition.size()).as("Partition %d", i).isLessThan(stringLiterals.size());
	        actualTotal.addAll(actualPartition);
        }
        assertSets(stringLiterals, actualTotal);
    }

    @Test
    public void testScanNonStringLiterals_CPOS() throws Exception {
        Resource ctx = vf.createIRI(CTX);
        RDFPredicate rdfPred = rdfFactory.createPredicate(RDF.VALUE);
        RDFContext rdfCtx = rdfFactory.createContext(ctx);

        Set<Literal> actual = new HashSet<>();
        Scan scan = stmtIndices.getCPOSIndex().scanWithConstraint(rdfCtx, rdfPred, StatementIndices.NO_PARTITIONING, 0, new LiteralConstraint(HALYARD.NON_STRING_TYPE), null);
        try (ResultScanner rs = keyspaceConn.getScanner(scan)) {
            Result r;
            while ((r = rs.next()) != null) {
                for (Statement stmt : parseStatements(null, rdfPred, null, rdfCtx, r)) {
                    assertTrue(stmt.getObject().isLiteral(), "Not a literal: "+stmt.getObject());
                    actual.add((Literal) stmt.getObject());
                }
            }
        }
        assertSets(nonstringLiterals, actual);
    }

    @Test
    public void testScanTriples_POS() throws Exception {
        RDFPredicate rdfPred = rdfFactory.createPredicate(RDFS.SEEALSO);

        Set<Triple> actual = new HashSet<>();
        Scan scan = stmtIndices.getPOSIndex().scanWithConstraint(rdfPred, StatementIndices.NO_PARTITIONING, 0, new ValueConstraint(ValueType.TRIPLE), null, null);
        try (ResultScanner rs = keyspaceConn.getScanner(scan)) {
            Result r;
            while ((r = rs.next()) != null) {
                for (Statement stmt : parseStatements(null, rdfPred, null, null, r)) {
                    assertTrue(stmt.getObject().isTriple(), "Not a triple: "+stmt.getObject());
                    actual.add((Triple) stmt.getObject());
                }
            }
        }
        assertSets(allTriples, actual);
    }

    @Test
    public void testScanAllPartitions_OSP() throws Exception {
        int nbits = 3;
        int numPartitions = (1 << nbits);
        List<Statement> actualTotal = new ArrayList<>();
        for (int i=0; i<numPartitions; i++) {
        	List<Statement> actualPartition = new ArrayList<>();
	        Scan scan = stmtIndices.getOSPIndex().scanWithConstraint(i, nbits, null, null, null, null);
	        try (ResultScanner rs = keyspaceConn.getScanner(scan)) {
	            Result r;
	            while ((r = rs.next()) != null) {
	                for (Statement stmt : parseStatements(r)) {
	                    actualPartition.add(stmt);
	                }
	            }
	        }
	        assertThat(actualPartition.size()).as("Partition %d", i).isLessThan(defaultGraphStatements.size());
	        actualTotal.addAll(actualPartition);
        }
        assertNoDuplicates(defaultGraphStatements, actualTotal);
    }

    @Test
    public void testScanStringLiterals_OSP() throws Exception {
        Set<Literal> actual = new HashSet<>();
        Scan scan = stmtIndices.getOSPIndex().scanWithConstraint(StatementIndices.NO_PARTITIONING, 0, new LiteralConstraint(XSD.STRING), null, null, null);
        try (ResultScanner rs = keyspaceConn.getScanner(scan)) {
            Result r;
            while ((r = rs.next()) != null) {
                for (Statement stmt : parseStatements(r)) {
                    assertTrue(stmt.getObject().isLiteral(), "Not a literal: "+stmt.getObject());
                    actual.add((Literal) stmt.getObject());
                }
            }
        }
        assertSets(stringLiterals, actual);
    }

    @Test
    public void testScanNonStringLiterals_COSP() throws Exception {
        Resource ctx = vf.createIRI(CTX);
        RDFContext rdfCtx = rdfFactory.createContext(ctx);

        Set<Literal> actual = new HashSet<>();
        Scan scan = stmtIndices.getCOSPIndex().scanWithConstraint(rdfCtx, StatementIndices.NO_PARTITIONING, 0, new LiteralConstraint(HALYARD.NON_STRING_TYPE), null, null);
        try (ResultScanner rs = keyspaceConn.getScanner(scan)) {
            Result r;
            while ((r = rs.next()) != null) {
                for (Statement stmt : parseStatements(null, null, null, rdfCtx, r)) {
                   assertTrue(stmt.getObject().isLiteral(), "Not a literal: "+stmt.getObject());
                   actual.add((Literal) stmt.getObject());
                }
            }
        }
        assertSets(nonstringLiterals, actual);
    }

    @Test
    public void testScanTriples_OSP() throws Exception {
        Set<Triple> actual = new HashSet<>();
        Scan scan = stmtIndices.getOSPIndex().scanWithConstraint(StatementIndices.NO_PARTITIONING, 0, new ValueConstraint(ValueType.TRIPLE), null, null, null);
        try (ResultScanner rs = keyspaceConn.getScanner(scan)) {
            Result r;
            while ((r = rs.next()) != null) {
                for (Statement stmt : parseStatements(r)) {
                    assertTrue(stmt.getObject().isTriple(), "Not a triple: "+stmt.getObject());
                    actual.add((Triple) stmt.getObject());
                }
            }
        }
        assertSets(allTriples, actual);
    }

    @Test
    public void testScanByKeyFilter0_2() throws Exception {
        Resource subj = vf.createIRI(SUBJ);
        Resource ctx = vf.createIRI(CTX);
        Scan scan = stmtIndices.getPOSIndex().scanWithConstraint(StatementIndices.NO_PARTITIONING, 0, null, rdfFactory.createObject(foobarLiteral), null, null);
        Set<Statement> actual = getStatements(scan);
        assertSets(Collections.singleton(vf.createStatement(subj, RDF.VALUE, foobarLiteral, ctx)), actual);
    }

    @Test
    public void testScanByKeyFilter0_3() throws Exception {
        Resource subj = vf.createIRI(SUBJ);
        Resource ctx = vf.createIRI(CTX);
        Scan scan = stmtIndices.getSPOIndex().scanWithConstraint(StatementIndices.NO_PARTITIONING, 0, null, null, rdfFactory.createObject(foobarLiteral), null);
        Set<Statement> actual = getStatements(scan);
        assertSets(Collections.singleton(vf.createStatement(subj, RDF.VALUE, foobarLiteral, ctx)), actual);
    }

    @Test
    public void testScanByKeyFilter0_4() throws Exception {
        Resource subj = vf.createIRI(SUBJ);
        Resource ctx = vf.createIRI(CTX);
        Scan scan = stmtIndices.getCSPOIndex().scanWithConstraint(StatementIndices.NO_PARTITIONING, 0, null, null, null, rdfFactory.createObject(foobarLiteral));
        Set<Statement> actual = getStatements(scan);
        Set<Statement> expected = new HashSet<>();
        expected.add(vf.createStatement(subj, RDF.VALUE, foobarLiteral, ctx));
        expected.add(vf.createStatement(subj, RDF.VALUE, foobarLiteral, HALYARD.TRIPLE_GRAPH_CONTEXT));
        assertSets(expected, actual);
    }

    @Test
    public void testScanByKeyFilter1_3() throws Exception {
        Resource subj = vf.createIRI(SUBJ);
        Resource ctx = vf.createIRI(CTX);
        RDFContext rdfCtx = rdfFactory.createContext(ctx);
        Scan scan = stmtIndices.getCPOSIndex().scanWithConstraint(rdfCtx, StatementIndices.NO_PARTITIONING, 0, null, rdfFactory.createObject(foobarLiteral), null);
        Set<Statement> actual = getStatements(scan);
        assertSets(Collections.singleton(vf.createStatement(subj, RDF.VALUE, foobarLiteral, ctx)), actual);
    }

    @Test
    public void testScanByKeyFilter1_4() throws Exception {
        Resource subj = vf.createIRI(SUBJ);
        Resource ctx = vf.createIRI(CTX);
        RDFContext rdfCtx = rdfFactory.createContext(ctx);
        Scan scan = stmtIndices.getCSPOIndex().scanWithConstraint(rdfCtx, StatementIndices.NO_PARTITIONING, 0, null, null, rdfFactory.createObject(foobarLiteral));
        Set<Statement> actual = getStatements(scan);
        assertSets(Collections.singleton(vf.createStatement(subj, RDF.VALUE, foobarLiteral, ctx)), actual);
    }

    @Test
    public void testScanByKeyFilter2_4() throws Exception {
        Resource subj = vf.createIRI(SUBJ);
        Resource ctx = vf.createIRI(CTX);
        RDFSubject rdfSubj = rdfFactory.createSubject(subj);
        RDFContext rdfCtx = rdfFactory.createContext(ctx);
        Scan scan = stmtIndices.getCSPOIndex().scanWithConstraint(rdfCtx, rdfSubj, StatementIndices.NO_PARTITIONING, 0, null, rdfFactory.createObject(foobarLiteral));
        Set<Statement> actual = getStatements(scan);
        assertSets(Collections.singleton(vf.createStatement(subj, RDF.VALUE, foobarLiteral, ctx)), actual);
    }

    @Test
    public void testGetSubject() throws Exception {
        Resource subj = vf.createIRI(SUBJ);
    	Resource actual = stmtIndices.getSubject(keyspaceConn, rdfFactory.id(subj), vf);
    	assertEquals(subj, actual);
    }

    @Test
    public void testGetPredicate() throws Exception {
    	IRI pred = RDF.VALUE;
    	IRI actual = stmtIndices.getPredicate(keyspaceConn, rdfFactory.id(pred), vf);
    	assertEquals(pred, actual);
    }

    @Test
    public void testGetObject() throws Exception {
    	Value obj = foobarLiteral;
    	Value actual = stmtIndices.getObject(keyspaceConn, rdfFactory.id(obj), vf);
    	assertEquals(obj, actual);
    }

    private Set<Statement> getStatements(Scan scan) throws IOException {
    	Set<Statement> stmts = new HashSet<>();
        try (ResultScanner rs = keyspaceConn.getScanner(scan)) {
            Result r;
            while ((r = rs.next()) != null) {
                for (Statement stmt : parseStatements(r)) {
                    stmts.add(stmt);
                }
            }
        }
        return stmts;
    }

    private static <E> void assertNoDuplicates(Set<E> expected, List<E> actual) {
    	assertEquals(expected.size(), actual.size());
    	assertSets(expected, new HashSet<>(actual));
    }

    private static <E> void assertSets(Set<E> expected, Set<E> actual) {
    	Set<E> diff = new HashSet<>(actual);
    	diff.removeAll(expected);
    	assertEquals(expected, actual, "Unexpected: "+diff);
    }
}
