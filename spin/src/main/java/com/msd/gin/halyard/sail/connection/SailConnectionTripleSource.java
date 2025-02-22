/*
 * Copyright 2016 Merck Sharp & Dohme Corp. a subsidiary of Merck & Co.,
 * Inc., Kenilworth, NJ, USA.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.msd.gin.halyard.sail.connection;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.sail.SailConnection;

import com.msd.gin.halyard.query.algebra.evaluation.CloseableTripleSource;

public class SailConnectionTripleSource implements CloseableTripleSource {

	private final SailConnection conn;
	private final boolean includeInferred;
	private final ValueFactory vf;

	public SailConnectionTripleSource(SailConnection conn, boolean includeInferred, ValueFactory vf) {
		this.conn = conn;
		this.includeInferred = includeInferred;
		this.vf = vf;
	}

	@Override
	public CloseableIteration<? extends Statement> getStatements(Resource subj, IRI pred, Value obj, Resource... contexts) throws QueryEvaluationException {
		return conn.getStatements(subj, pred, obj, includeInferred, contexts);
	}

	@Override
	public ValueFactory getValueFactory() {
		return vf;
	}

	@Override
	public void close() {
		conn.close();
	}
}
