/*******************************************************************************
 * Copyright (c) 2015 Eclipse RDF4J contributors, Aduna, and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *******************************************************************************/
package com.msd.gin.halyard.sail.connection;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.QueryResults;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.query.TupleQueryResultHandler;
import org.eclipse.rdf4j.query.TupleQueryResultHandlerException;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.impl.IteratingTupleQueryResult;
import org.eclipse.rdf4j.query.parser.ParsedTupleQuery;
import org.eclipse.rdf4j.sail.SailConnection;
import org.eclipse.rdf4j.sail.SailException;

import com.msd.gin.halyard.query.QueueingBindingSetPipe;
import com.msd.gin.halyard.sail.BindingSetPipeSailConnection;

/**
 * @author Arjohn Kampman
 */
public class SailConnectionTupleQuery extends SailConnectionQuery implements TupleQuery {
	private static final int MAX_QUEUE_SIZE = 64;

	public SailConnectionTupleQuery(ParsedTupleQuery tupleQuery, SailConnection sailConnection) {
		super(tupleQuery, sailConnection);
	}

	@Override
	public ParsedTupleQuery getParsedQuery() {
		return (ParsedTupleQuery) super.getParsedQuery();
	}

	@Override
	public TupleQueryResult evaluate() throws QueryEvaluationException {
		TupleExpr tupleExpr = getParsedQuery().getTupleExpr();

		try {
			CloseableIteration<? extends BindingSet, QueryEvaluationException> bindingsIter;

			SailConnection sailCon = getSailConnection();
			bindingsIter = sailCon.evaluate(tupleExpr, getActiveDataset(), getBindings(), getIncludeInferred());

			bindingsIter = enforceMaxQueryTime(bindingsIter);

			return new IteratingTupleQueryResult(new ArrayList<>(tupleExpr.getBindingNames()), bindingsIter);
		} catch (SailException e) {
			throw new QueryEvaluationException(e.getMessage(), e);
		}
	}

	@Override
	public void evaluate(TupleQueryResultHandler handler)
			throws QueryEvaluationException, TupleQueryResultHandlerException {
		SailConnection sailCon = getSailConnection();
		if (sailCon instanceof BindingSetPipeSailConnection) {
			TupleExpr tupleExpr = getParsedQuery().getTupleExpr();
			int maxExecutionTime = getMaxExecutionTime();
			long timeout = maxExecutionTime > 0 ? maxExecutionTime : Integer.MAX_VALUE;
			QueueingBindingSetPipe pipe = new QueueingBindingSetPipe(MAX_QUEUE_SIZE, timeout, TimeUnit.SECONDS);
			((BindingSetPipeSailConnection) sailCon).evaluate(pipe, tupleExpr, getActiveDataset(), getBindings(), getIncludeInferred());
			handler.startQueryResult(new ArrayList<>(tupleExpr.getBindingNames()));
			pipe.collect(next -> {
				handler.handleSolution(next);
			});
			handler.endQueryResult();
		} else {
			TupleQueryResult queryResult = evaluate();
			QueryResults.report(queryResult, handler);
		}
	}
}
