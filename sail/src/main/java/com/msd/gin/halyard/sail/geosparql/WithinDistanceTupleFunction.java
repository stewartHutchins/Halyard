package com.msd.gin.halyard.sail.geosparql;

import com.msd.gin.halyard.common.RDFFactory;
import com.msd.gin.halyard.common.StatementIndices;
import com.msd.gin.halyard.model.WKTLiteral;
import com.msd.gin.halyard.model.vocabulary.HALYARD;
import com.msd.gin.halyard.query.algebra.evaluation.ExtendedTripleSource;
import com.msd.gin.halyard.query.algebra.evaluation.function.ExtendedTupleFunction;
import com.msd.gin.halyard.sail.search.SearchClient;
import com.msd.gin.halyard.sail.search.SearchDocument;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.common.iteration.CloseableIteratorIteration;
import org.eclipse.rdf4j.common.iteration.ConvertingIteration;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.vocabulary.GEOF;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.algebra.evaluation.TripleSource;
import org.eclipse.rdf4j.query.algebra.evaluation.function.TupleFunction;
import org.kohsuke.MetaInfServices;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.spatial4j.distance.DistanceUtils;

import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;

@MetaInfServices(TupleFunction.class)
public class WithinDistanceTupleFunction implements ExtendedTupleFunction {
	@Override
	public String getURI() {
		return HALYARD.WITHIN_DISTANCE.stringValue();
	}

	@Override
	public CloseableIteration<? extends List<? extends Value>> evaluate(TripleSource tripleSource, Value... args) throws QueryEvaluationException {
		ExtendedTripleSource extTripleSource = (ExtendedTripleSource) tripleSource;

		if (args.length < 3) {
			throw new QueryEvaluationException("Missing arguments");
		}

		if (!args[0].isLiteral()) {
			throw new QueryEvaluationException("Invalid geometry value");
		}

		if (!args[1].isLiteral()) {
			throw new QueryEvaluationException("Invalid distance value");
		}

		Literal from = ((Literal) args[0]);
		double distLimit = ((Literal) args[1]).doubleValue();
		Value units = args[2];
		boolean inclDistance = (args.length == 4) && HALYARD.DISTANCE.equals(args[3]);

		Coordinate fromCoord = WKTLiteral.geometryValue(from).getCoordinate();

		String esUnits;
		double esDistLimit;
		if (GEOF.UOM_METRE.equals(units)) {
			esUnits = "m";
			esDistLimit = distLimit;
		} else if (GEOF.UOM_DEGREE.equals(units)) {
			esUnits = "km";
			esDistLimit = DistanceUtils.degrees2Dist(distLimit, DistanceUtils.EARTH_MEAN_RADIUS_KM);
		} else if (GEOF.UOM_RADIAN.equals(units)) {
			esUnits = "km";
			esDistLimit = DistanceUtils.radians2Dist(distLimit, DistanceUtils.EARTH_MEAN_RADIUS_KM);
		} else if (GEOF.UOM_UNITY.equals(units)) {
			esUnits = "km";
			esDistLimit = distLimit * Math.PI * DistanceUtils.EARTH_MEAN_RADIUS_KM;
		} else {
			throw new QueryEvaluationException("Unsupported units: " + units);
		}

		double fromLatRad, fromLonRad;
		if (inclDistance) {
			fromLatRad = DistanceUtils.toRadians(fromCoord.getY());
			fromLonRad = DistanceUtils.toRadians(fromCoord.getX());
		} else {
			fromLatRad = Double.NaN;
			fromLonRad = Double.NaN;
		}

		ValueFactory valueFactory = extTripleSource.getValueFactory();
		StatementIndices indices = extTripleSource.getQueryHelper(StatementIndices.class);
		RDFFactory rdfFactory = indices.getRDFFactory();
		SearchClient searchClient = extTripleSource.getQueryHelper(SearchClient.class);

		try {
			SearchResponse<? extends SearchDocument> searchResults = searchClient.search(fromCoord.getY(), fromCoord.getX(), esDistLimit, esUnits);
			return new ConvertingIteration<Hit<? extends SearchDocument>, List<Value>>(
					new CloseableIteratorIteration<Hit<? extends SearchDocument>>(searchResults.hits().hits().iterator())) {
				@Override
				protected List<Value> convert(Hit<? extends SearchDocument> doc) throws QueryEvaluationException {
					Literal to = (Literal) doc.source().createValue(valueFactory, rdfFactory);
					if (inclDistance) {
						Coordinate toCoord = WKTLiteral.geometryValue(to).getCoordinate();
						double toLatRad = DistanceUtils.toRadians(toCoord.getY());
						double toLonRad = DistanceUtils.toRadians(toCoord.getX());
						double distRad = DistanceUtils.distVincentyRAD(fromLatRad, fromLonRad, toLatRad, toLonRad);
						double dist;
						if (GEOF.UOM_METRE.equals(units)) {
							dist = DistanceUtils.radians2Dist(distRad, DistanceUtils.EARTH_MEAN_RADIUS_KM) * 1000;
						} else if (GEOF.UOM_DEGREE.equals(units)) {
							dist = DistanceUtils.toDegrees(distRad);
						} else if (GEOF.UOM_RADIAN.equals(units)) {
							dist = distRad;
						} else if (GEOF.UOM_UNITY.equals(units)) {
							dist = distRad / Math.PI;
						} else {
							throw new QueryEvaluationException("Unsupported units: " + units);
						}
						return Arrays.asList(to, valueFactory.createLiteral(dist));
					} else {
						return Collections.singletonList(to);
					}
				}
			};
		} catch (IOException e) {
			throw new QueryEvaluationException(e);
		}
	}
}
