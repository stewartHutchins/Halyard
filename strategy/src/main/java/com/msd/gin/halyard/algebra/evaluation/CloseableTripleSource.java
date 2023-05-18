package com.msd.gin.halyard.algebra.evaluation;

import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.algebra.evaluation.TripleSource;

public interface CloseableTripleSource extends TripleSource, AutoCloseable {
	@Override
	void close() throws QueryEvaluationException;
}
