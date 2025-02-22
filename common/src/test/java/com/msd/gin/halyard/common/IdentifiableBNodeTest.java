package com.msd.gin.halyard.common;

import java.util.Objects;

import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.BNodeTest;

public class IdentifiableBNodeTest extends BNodeTest {

	@Override
	protected BNode bnode(String id) {
		Objects.requireNonNull(id);
		return new IdentifiableBNode(id);
	}

}
