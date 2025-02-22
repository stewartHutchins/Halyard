/*******************************************************************************
 * Copyright (c) 2015 Eclipse RDF4J contributors, Aduna, and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 *******************************************************************************/
package com.msd.gin.halyard.spin.function.spif;

import org.eclipse.rdf4j.model.vocabulary.SPIF;

public class UpperCase extends AbstractStringReplacer {

	public UpperCase() {
		super(SPIF.UPPER_CASE_FUNCTION.toString());
	}

	@Override
	protected String transform(String s) {
		return s.toUpperCase();
	}
}
