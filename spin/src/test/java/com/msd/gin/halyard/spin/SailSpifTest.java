package com.msd.gin.halyard.spin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Locale;

import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.BooleanQuery;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.helpers.NTriplesUtil;
import org.eclipse.rdf4j.sail.Sail;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.msd.gin.halyard.model.ObjectArrayLiteral;
import com.msd.gin.halyard.model.TupleLiteral;

/**
 * Runs the spif test cases.
 */
public class SailSpifTest {

	private Repository repo;

	private RepositoryConnection conn;

	/**
	 * Temporary storage of platform locale. See #280 . Some tests (e.g. test involving spif:dateFormat) require English locale to succeed, instead of platform locale.
	 */
	private final Locale platformLocale = Locale.getDefault();

	@Before
	public final void setup() throws Exception {
		repo = new SailRepository(createSail());
		repo.init();
		conn = repo.getConnection();

		/*
		 * FIXME See #280 . Some tests (e.g. test involving spif:dateFormat) require English locale to succeed, instead of platform locale.
		 */
		Locale.setDefault(Locale.ENGLISH);
	}

	protected Sail createSail() throws Exception {
		return new SpinMemoryStore();
	}

	@After
	public final void tearDown() throws Exception {
		Locale.setDefault(platformLocale);
		if (conn != null) {
			conn.close();
		}
		if (repo != null) {
			repo.shutDown();
		}
		postCleanup();
	}

	protected void postCleanup() throws Exception {
	}

	@Test
	public void runTests() throws Exception {
		SpinInferencing.insertSchema(conn);

		TupleQuery tq = conn.prepareTupleQuery(QueryLanguage.SPARQL,
				"prefix spin: <http://spinrdf.org/spin#> " + "prefix spl: <http://spinrdf.org/spl#> "
						+ "select ?testCase ?expected ?actual where {?testCase a spl:TestCase. ?testCase spl:testResult ?expected. ?testCase spl:testExpression ?expr. " + "BIND(spin:eval(?expr) as ?actual) "
						+ "FILTER(?expected != ?actual) " + "FILTER(strstarts(str(?testCase), 'http://spinrdf.org/spif#'))}");
		try (TupleQueryResult tqr = tq.evaluate()) {
			while (tqr.hasNext()) {
				BindingSet bs = tqr.next();
				Value testCase = bs.getValue("testCase");
				Value expected = bs.getValue("expected");
				Value actual = bs.getValue("actual");
				assertEquals(testCase.stringValue(), expected, actual);
			}
		}
	}

	@Test
	public void testTitleCase() throws Exception {
		BooleanQuery bq = conn.prepareBooleanQuery(QueryLanguage.SPARQL, "prefix spif: <http://spinrdf.org/spif#> " + "ask where {filter(spif:titleCase('foo bar') = 'Foo Bar')}");
		assertTrue(bq.evaluate());
	}

	@Test
	public void testTitleCaseWithPattern() throws Exception {
		BooleanQuery bq = conn.prepareBooleanQuery(QueryLanguage.SPARQL, "prefix spif: <http://spinrdf.org/spif#> " + "ask where {filter(spif:titleCase('foo bar', 'o+') = 'fOo bar')}");
		assertTrue(bq.evaluate());
	}

	@Test
	public void testCast() throws Exception {
		BooleanQuery bq = conn.prepareBooleanQuery(QueryLanguage.SPARQL, "prefix spif: <http://spinrdf.org/spif#> " + "ask where {filter(spif:cast(3.14, xsd:integer) = 3)}");
		assertTrue(bq.evaluate());
	}

	@Test
	public void testIndexOf() throws Exception {
		BooleanQuery bq = conn.prepareBooleanQuery(QueryLanguage.SPARQL, "prefix spif: <http://spinrdf.org/spif#> " + "ask where {filter(spif:indexOf('test', 't', 2) = 3)}");
		assertTrue(bq.evaluate());
	}

	@Test
	public void testLastIndexOf() throws Exception {
		BooleanQuery bq = conn.prepareBooleanQuery(QueryLanguage.SPARQL, "prefix spif: <http://spinrdf.org/spif#> " + "ask where {filter(spif:lastIndexOf('test', 't') = 3)}");
		assertTrue(bq.evaluate());
	}

	@Test
	public void testEncodeURL() throws Exception {
		BooleanQuery bq = conn.prepareBooleanQuery(QueryLanguage.SPARQL, "prefix spif: <http://spinrdf.org/spif#> " + "ask where {filter(spif:encodeURL('Hello world') = 'Hello+world')}");
		assertTrue(bq.evaluate());
	}

	@Test
	public void testBuildString() throws Exception {
		BooleanQuery bq = conn.prepareBooleanQuery(QueryLanguage.SPARQL, "prefix spif: <http://spinrdf.org/spif#> " + "ask where {filter(spif:buildString('{?1} {?2}', 'Hello', 'world') = 'Hello world')}");
		assertTrue(bq.evaluate());
	}

	@Test
	public void testBuildURI() throws Exception {
		BooleanQuery bq = conn.prepareBooleanQuery(QueryLanguage.SPARQL,
				"prefix spif: <http://spinrdf.org/spif#> " + "ask where {filter(spif:buildURI('<http://example.org/{?1}#{?2}>', 'schema', 'prop') = <http://example.org/schema#prop>)}");
		assertTrue(bq.evaluate());
	}

	@Test
	public void testName() throws Exception {
		ValueFactory vf = conn.getValueFactory();
		conn.add(vf.createIRI("http://whatever/", "foobar"), RDFS.LABEL, vf.createLiteral("foobar"));
		BooleanQuery bq = conn.prepareBooleanQuery(QueryLanguage.SPARQL, "prefix spif: <http://spinrdf.org/spif#> " + "ask where {?s rdfs:label ?l. filter(spif:name(?s) = ?l)}");
		assertTrue(bq.evaluate());
	}

	@Test
	public void testForEach() throws Exception {
		TupleQuery tq = conn.prepareTupleQuery(QueryLanguage.SPARQL, "prefix spif: <http://spinrdf.org/spif#> " + "select ?x where {?x spif:foreach (1 2 3)}");
		try (TupleQueryResult tqr = tq.evaluate()) {
			for (int i = 1; i <= 3; i++) {
				BindingSet bs = tqr.next();
				assertThat(((Literal) bs.getValue("x")).intValue()).isEqualTo(i);
			}
			assertFalse(tqr.hasNext());
		}
	}

	@Test
	public void testForEachTupleLiteral() throws Exception {
		ValueFactory vf = conn.getValueFactory();
		String tl = NTriplesUtil.toNTriplesString(new TupleLiteral(vf.createLiteral(2), vf.createLiteral(3)));
		TupleQuery tq = conn.prepareTupleQuery(QueryLanguage.SPARQL, "prefix spif: <http://spinrdf.org/spif#> prefix halyard: <http://merck.github.io/Halyard/ns#> " + "select ?x where {?x spif:foreach (1 "+ tl + " 4)}");
		try (TupleQueryResult tqr = tq.evaluate()) {
			for (int i = 1; i <= 4; i++) {
				BindingSet bs = tqr.next();
				assertThat(((Literal) bs.getValue("x")).intValue()).isEqualTo(i);
			}
			assertFalse(tqr.hasNext());
		}
	}

	@Test
	public void testForEachArrayLiteral() throws Exception {
		String al = NTriplesUtil.toNTriplesString(new ObjectArrayLiteral(2, 3));
		TupleQuery tq = conn.prepareTupleQuery(QueryLanguage.SPARQL, "prefix spif: <http://spinrdf.org/spif#> prefix halyard: <http://merck.github.io/Halyard/ns#> " + "select ?x where {?x spif:foreach (1 "+ al + " 4)}");
		try (TupleQueryResult tqr = tq.evaluate()) {
			for (int i = 1; i <= 4; i++) {
				BindingSet bs = tqr.next();
				assertThat(((Literal) bs.getValue("x")).intValue()).isEqualTo(i);
			}
			assertFalse(tqr.hasNext());
		}
	}

	@Test
	public void testFor() throws Exception {
		TupleQuery tq = conn.prepareTupleQuery(QueryLanguage.SPARQL, "prefix spif: <http://spinrdf.org/spif#> " + "select ?x where {?x spif:for (1 4)}");
		try (TupleQueryResult tqr = tq.evaluate()) {
			for (int i = 1; i <= 4; i++) {
				BindingSet bs = tqr.next();
				assertThat(((Literal) bs.getValue("x")).intValue()).isEqualTo(i);
			}
			assertFalse(tqr.hasNext());
		}
	}

	@Test
	public void testSplit() throws Exception {
		TupleQuery tq = conn.prepareTupleQuery(QueryLanguage.SPARQL, "prefix spif: <http://spinrdf.org/spif#> " + "select ?x where {?x spif:split ('1,2,3' ',')}");
		try (TupleQueryResult tqr = tq.evaluate()) {
			for (int i = 1; i <= 3; i++) {
				BindingSet bs = tqr.next();
				assertThat(bs.getValue("x").stringValue()).isEqualTo(Integer.toString(i));
			}
			assertFalse(tqr.hasNext());
		}
	}

	@Test
	public void testCanInvoke() throws Exception {
		SpinInferencing.insertSchema(conn);
		BooleanQuery bq = conn.prepareBooleanQuery(QueryLanguage.SPARQL, "prefix spif: <http://spinrdf.org/spif#> " + "ask where {filter(spif:canInvoke(spif:indexOf, 'foobar', 'b'))}");
		assertTrue(bq.evaluate());
	}

	@Test
	public void testCantInvoke() throws Exception {
		SpinInferencing.insertSchema(conn);
		BooleanQuery bq = conn.prepareBooleanQuery(QueryLanguage.SPARQL, "prefix spif: <http://spinrdf.org/spif#> " + "ask where {filter(spif:canInvoke(spif:indexOf, 'foobar', 2))}");
		assertFalse(bq.evaluate());
	}
}
