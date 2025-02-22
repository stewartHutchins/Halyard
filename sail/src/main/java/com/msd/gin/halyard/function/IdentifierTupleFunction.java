package com.msd.gin.halyard.function;

import com.msd.gin.halyard.common.ByteUtils;
import com.msd.gin.halyard.common.RDFFactory;
import com.msd.gin.halyard.common.StatementIndices;
import com.msd.gin.halyard.model.vocabulary.HALYARD;
import com.msd.gin.halyard.query.algebra.evaluation.ExtendedTripleSource;
import com.msd.gin.halyard.query.algebra.evaluation.function.ExtendedTupleFunction;

import java.util.Collections;
import java.util.List;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.common.iteration.SingletonIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Namespace;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.algebra.evaluation.TripleSource;
import org.eclipse.rdf4j.query.algebra.evaluation.ValueExprEvaluationException;
import org.eclipse.rdf4j.query.algebra.evaluation.function.TupleFunction;
import org.kohsuke.MetaInfServices;

@MetaInfServices(TupleFunction.class)
public class IdentifierTupleFunction implements ExtendedTupleFunction {
	@Override
	public String getURI() {
		return HALYARD.IDENTIFIER_PROPERTY.stringValue();
	}

	@Override
	public CloseableIteration<? extends List<? extends Value>> evaluate(TripleSource tripleSource,
			Value... args)
		throws ValueExprEvaluationException
	{
		ExtendedTripleSource extTripleSource = (ExtendedTripleSource) tripleSource;
		StatementIndices indices = extTripleSource.getQueryHelper(StatementIndices.class);
		RDFFactory rdfFactory = indices.getRDFFactory();

		Namespace ns;
		String id;
		if (args.length == 1) {
			ns = HALYARD.VALUE_ID_NS;
			id = rdfFactory.id(args[0]).toString();
		} else if (args.length == 3) {
			if (!(args[0] instanceof Resource)) {
				throw new ValueExprEvaluationException("First argument must be a subject");
			}
			if (!(args[1] instanceof IRI)) {
				throw new ValueExprEvaluationException("Second argument must be a predicate");
			}
			ns = HALYARD.STATEMENT_ID_NS;
			byte[] stmtId = rdfFactory.statementId((Resource) args[0], (IRI) args[1], args[2]);
			id = ByteUtils.encode(stmtId);
		} else {
			throw new ValueExprEvaluationException(String.format("%s requires 1 or 3 arguments, got %d", getURI(), args.length));
		}

		return new SingletonIteration<>(Collections.singletonList(extTripleSource.getValueFactory().createIRI(ns.getName(), id)));
	}
}
