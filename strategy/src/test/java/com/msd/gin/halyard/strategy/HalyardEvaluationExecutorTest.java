package com.msd.gin.halyard.strategy;

import com.msd.gin.halyard.algebra.ServiceRoot;

import java.util.Collections;

import org.apache.hadoop.conf.Configuration;
import org.eclipse.rdf4j.query.algebra.Join;
import org.eclipse.rdf4j.query.algebra.QueryRoot;
import org.eclipse.rdf4j.query.algebra.Service;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.impl.EmptyBindingSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class HalyardEvaluationExecutorTest {
	private HalyardEvaluationExecutor executor;

	private StatementPattern createStatementPattern(String s, String p, String o) {
		return new StatementPattern(new Var(s), new Var(p), new Var(o));
	}

	private StatementPattern createStatementPattern(int i) {
		return createStatementPattern("s"+i, "p"+i, "o"+i);
	}

	@BeforeEach
	public void setUp() {
		executor = new HalyardEvaluationExecutor(new Configuration());
	}

	@AfterEach
	public void tearDown() {
		executor.shutdown();
	}

	@Test
	public void testJoinPriority() {
		StatementPattern sp1 = createStatementPattern(1);
		StatementPattern sp2 = createStatementPattern(2);
		StatementPattern sp3 = createStatementPattern(3);
		Join j2 = new Join(sp2, sp3);
		Join j1 = new Join(sp1, j2);
		QueryRoot root = new QueryRoot(j1);
		assertEquals(1, executor.getPriorityForNode(j1));
		assertEquals(2, executor.getPriorityForNode(sp1));
		assertEquals(3, executor.getPriorityForNode(j2));
		assertEquals(4, executor.getPriorityForNode(sp2));
		assertEquals(5, executor.getPriorityForNode(sp3));
	}

	@Test
	public void testServicePriority() {
		StatementPattern sp1 = createStatementPattern(1);
		StatementPattern sp2 = createStatementPattern(2);
		StatementPattern sp3 = createStatementPattern(3);
		StatementPattern sp4 = createStatementPattern(3);
		Join j3 = new Join(sp3, sp4);
		Service service = new Service(new Var("url"), j3, "# query string not used by test", Collections.emptyMap(), null, false);
		Join j2 = new Join(sp2, service);
		Join j1 = new Join(sp1, j2);
		QueryRoot root = new QueryRoot(j1);
		assertEquals(1, executor.getPriorityForNode(j1));
		assertEquals(2, executor.getPriorityForNode(sp1));
		assertEquals(3, executor.getPriorityForNode(j2));
		assertEquals(4, executor.getPriorityForNode(sp2));
		assertEquals(5, executor.getPriorityForNode(service));
		assertEquals(6, executor.getPriorityForNode(j3));
		assertEquals(7, executor.getPriorityForNode(sp3));
		assertEquals(8, executor.getPriorityForNode(sp4));

		ServiceRoot serviceRoot = ServiceRoot.create(service);
		Join serviceJoin = (Join) serviceRoot.getArg();
		assertEquals(6, executor.getPriorityForNode(serviceJoin));
		assertEquals(7, executor.getPriorityForNode(serviceJoin.getLeftArg()));
		assertEquals(8, executor.getPriorityForNode(serviceJoin.getRightArg()));
	}

	@Test
	public void testPrintQueryNode() {
		HalyardEvaluationExecutor.printQueryNode(createStatementPattern(1), EmptyBindingSet.getInstance());
	};

	@Test
	public void testThreadPool() {
		int tasks = executor.getThreadPoolExecutor().getActiveCount();
		assertEquals(0, tasks);
		assertEquals(tasks, executor.getThreadPoolExecutor().getThreadDump().length);
		assertEquals(0, executor.getThreadPoolExecutor().getQueueDump().length);
	}
}
