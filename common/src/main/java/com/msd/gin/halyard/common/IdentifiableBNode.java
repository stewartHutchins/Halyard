package com.msd.gin.halyard.common;

import org.eclipse.rdf4j.model.BNode;

public final class IdentifiableBNode extends IdentifiableValue implements BNode {
	IdentifiableBNode(ValueIdentifier id, ByteArray ser, RDFFactory rdfFactory) {
		super(id, ser, rdfFactory);
	}

	IdentifiableBNode(String id) {
		super(MATERIALIZED_VALUE_FACTORY.createBNode(id));
	}

	@Override
	public String getID() {
		return ((BNode)getValue()).getID();
	}
}
