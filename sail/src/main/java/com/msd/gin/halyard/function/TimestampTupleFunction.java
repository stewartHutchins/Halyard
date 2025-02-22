package com.msd.gin.halyard.function;

import static com.msd.gin.halyard.model.vocabulary.HALYARD.*;

import com.msd.gin.halyard.common.Timestamped;
import com.msd.gin.halyard.query.algebra.evaluation.function.ExtendedTupleFunction;
import com.msd.gin.halyard.sail.HBaseTripleSource;
import com.msd.gin.halyard.spin.function.AbstractSpinFunction;

import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.common.iteration.ConvertingIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Triple;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.algebra.evaluation.TripleSource;
import org.eclipse.rdf4j.query.algebra.evaluation.ValueExprEvaluationException;
import org.eclipse.rdf4j.query.algebra.evaluation.function.TupleFunction;
import org.kohsuke.MetaInfServices;

@MetaInfServices(TupleFunction.class)
public class TimestampTupleFunction extends AbstractSpinFunction implements ExtendedTupleFunction {

	public TimestampTupleFunction() {
		super(TIMESTAMP_PROPERTY.stringValue());
	}

	@Override
	public CloseableIteration<? extends List<? extends Value>> evaluate(TripleSource tripleSource, Value... args) throws ValueExprEvaluationException {
		ValueFactory vf = tripleSource.getValueFactory();
		if (args.length == 1 && args[0].isTriple()) {
			Triple t = (Triple) args[0];
			args = new Value[] { t.getSubject(), t.getPredicate(), t.getObject() };
		}

		if (args.length < 3 || args.length > 4) {
			throw new ValueExprEvaluationException(String.format("%s requires 3 or 4 arguments, got %d", getURI(), args.length));
		}

		if (!args[0].isResource()) {
			throw new ValueExprEvaluationException("First argument must be a subject");
		}
		if (!args[1].isIRI()) {
			throw new ValueExprEvaluationException("Second argument must be a predicate");
		}
		if (args.length == 4 && !args[3].isResource()) {
			throw new ValueExprEvaluationException("Fourth argument must be a context");
		}

		Resource[] contexts = (args.length == 4) ? new Resource[] { (Resource) args[3] } : new Resource[0];
		CloseableIteration<? extends Statement> iter = ((HBaseTripleSource) tripleSource).getTimestampedTripleSource().getStatements((Resource) args[0], (IRI) args[1], args[2], contexts);
		return new ConvertingIteration<Statement, List<? extends Value>>(iter) {
			@Override
			protected List<? extends Value> convert(Statement stmt) throws QueryEvaluationException {
				long ts = ((Timestamped) stmt).getTimestamp();
				return Collections.singletonList(vf.createLiteral(new Date(ts)));
			}
		};
	}
}
