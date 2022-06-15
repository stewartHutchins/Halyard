package com.msd.gin.halyard.function;

import java.io.IOException;

import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.query.algebra.evaluation.function.TupleFunction;
import org.kohsuke.MetaInfServices;

import com.msd.gin.halyard.common.HalyardTableUtils;
import com.msd.gin.halyard.common.KeyspaceConnection;
import com.msd.gin.halyard.common.StatementIndices;
import com.msd.gin.halyard.common.ValueIdentifier;

@MetaInfServices(TupleFunction.class)
public final class SubjectTupleFunction extends AbstractReificationTupleFunction {

	@Override
	public String getURI() {
		return RDF.SUBJECT.stringValue();
	}

	@Override
	protected int statementPosition() {
		return 0;
	}

	@Override
	protected Value getValue(KeyspaceConnection ks, ValueIdentifier id, ValueFactory vf, StatementIndices stmtIndices) throws IOException {
		return HalyardTableUtils.getSubject(ks, id, vf, stmtIndices);
	}
}
