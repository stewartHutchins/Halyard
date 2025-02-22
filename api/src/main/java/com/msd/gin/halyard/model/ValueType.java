package com.msd.gin.halyard.model;

import org.eclipse.rdf4j.model.Value;

public enum ValueType {
	LITERAL,
	TRIPLE,
	IRI,
	BNODE;

	public static ValueType valueOf(Value v) {
		if (v.isIRI()) {
			return IRI;
		} else if (v.isLiteral()) {
			return LITERAL;
		} else if (v.isTriple()) {
			return TRIPLE;
		} else if (v.isBNode()) {
			return BNODE;
		}
		throw new AssertionError();
	}
}
