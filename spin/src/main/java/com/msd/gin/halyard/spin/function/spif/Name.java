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

import java.util.stream.Stream;

import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.model.vocabulary.SPIF;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.algebra.evaluation.TripleSource;
import org.eclipse.rdf4j.query.algebra.evaluation.ValueExprEvaluationException;
import org.eclipse.rdf4j.query.algebra.evaluation.function.Function;
import org.eclipse.rdf4j.query.algebra.evaluation.util.TripleSources;

import com.msd.gin.halyard.query.algebra.evaluation.ExtendedTripleSource;
import com.msd.gin.halyard.query.algebra.evaluation.QueryPreparer;
import com.msd.gin.halyard.spin.function.AbstractSpinFunction;

public class Name extends AbstractSpinFunction implements Function {

	public Name() {
		super(SPIF.NAME_FUNCTION.stringValue());
	}

	@Override
	public Value evaluate(ValueFactory valueFactory, Value... args) throws ValueExprEvaluationException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Value evaluate(TripleSource tripleSource, Value... args) throws ValueExprEvaluationException {
		ValueFactory valueFactory = tripleSource.getValueFactory();
		if (args.length != 1) {
			throw new ValueExprEvaluationException(
					String.format("%s requires 1 argument, got %d", getURI(), args.length));
		}
		if (args[0] instanceof Literal) {
			return valueFactory.createLiteral(((Literal) args[0]).getLabel());
		} else {
			ExtendedTripleSource extTripleSource = (ExtendedTripleSource) tripleSource;
			try (QueryPreparer qp = extTripleSource.newQueryPreparer()) {
				try (Stream<Literal> stream = TripleSources
						.getObjectLiterals((Resource) args[0], RDFS.LABEL, extTripleSource)
						.stream()) {
					return stream.findFirst().orElseGet(() -> valueFactory.createLiteral(args[0].stringValue()));
				}

			} catch (QueryEvaluationException e) {
				throw new ValueExprEvaluationException(e);
			}
		}
	}
}
