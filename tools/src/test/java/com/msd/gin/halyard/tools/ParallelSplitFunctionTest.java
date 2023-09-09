/*
 * Copyright 2018 Merck Sharp & Dohme Corp. a subsidiary of Merck & Co.,
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
package com.msd.gin.halyard.tools;

import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.algebra.evaluation.ValueExprEvaluationException;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.msd.gin.halyard.vocab.HALYARD.PARALLEL_SPLIT_FUNCTION;

/**
 *
 * @author Adam Sotona (MSD)
 */
public class ParallelSplitFunctionTest {

    private static final SimpleValueFactory SVF = SimpleValueFactory.getInstance();

    @Test
    public void testGetURI() {
        assertEquals(PARALLEL_SPLIT_FUNCTION.stringValue(), new ParallelSplitFunction(1).getURI());
    }

    @Test(expected = ValueExprEvaluationException.class)
    public void testEvaluateNoArgs() {
        new ParallelSplitFunction(1).evaluate(SVF);
    }

    @Test(expected = ValueExprEvaluationException.class)
    public void testEvaluateNullArgs() {
        new ParallelSplitFunction(1).evaluate(SVF, SVF.createLiteral(10), null);
    }

    @Test(expected = ValueExprEvaluationException.class)
    public void testEvaluateSingleArg() {
        new ParallelSplitFunction(1).evaluate(SVF, SVF.createLiteral(10));
    }

    @Test(expected = ValueExprEvaluationException.class)
    public void testEvaluateNegativeArg() {
        new ParallelSplitFunction(1).evaluate(SVF, SVF.createLiteral(-1), SVF.createLiteral("hello"));
    }

    @Test(expected = ValueExprEvaluationException.class)
    public void testEvaluateNANArg() {
        new ParallelSplitFunction(1).evaluate(SVF, SVF.createLiteral("not a number"), SVF.createLiteral("hello"));
    }

    @Test
    public void testEvaluate0() {
        assertFalse(((Literal)new ParallelSplitFunction(0).evaluate(SVF, SVF.createLiteral(3), SVF.createLiteral("hello"))).booleanValue());
    }

    @Test
    public void testEvaluate1() {
        assertFalse(((Literal)new ParallelSplitFunction(1).evaluate(SVF, SVF.createLiteral(3), SVF.createLiteral("hello"))).booleanValue());
    }

    @Test
    public void testEvaluate2() {
        assertTrue(((Literal)new ParallelSplitFunction(2).evaluate(SVF, SVF.createLiteral(3), SVF.createLiteral("hello"))).booleanValue());
    }

    @Test
    public void testGetNumberOfForksFromFunction() {
        assertEquals(5, ParallelSplitFunction.getNumberOfForksFromFunctionArgument("select * where {?s ?p ?o. filter <" + PARALLEL_SPLIT_FUNCTION.stringValue() + ">(5, ?s)}", -1));
    }

    @Test
    public void testGetNumberOfForksFromSelectWithoutFunction() {
        assertEquals(0, ParallelSplitFunction.getNumberOfForksFromFunctionArgument("select * where {?s ?p ?o.}", -1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetNumberOfForksNoArgs() {
        ParallelSplitFunction.getNumberOfForksFromFunctionArgument("select * where {?s ?p ?o. filter <" + PARALLEL_SPLIT_FUNCTION.stringValue() + ">()}", -1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetNumberOfForksNANArg() {
        ParallelSplitFunction.getNumberOfForksFromFunctionArgument("select * where {?s ?p ?o. filter <" + PARALLEL_SPLIT_FUNCTION.stringValue() + ">(\"not a number\", ?s)}", -1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetNumberOfForksVarArg() {
        ParallelSplitFunction.getNumberOfForksFromFunctionArgument("select * where {?s ?p ?o. filter <" + PARALLEL_SPLIT_FUNCTION.stringValue() + ">(?p, ?s)}", -1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetNumberOfForksNegativeArg() {
        ParallelSplitFunction.getNumberOfForksFromFunctionArgument("select * where {?s ?p ?o. filter <" + PARALLEL_SPLIT_FUNCTION.stringValue() + ">(-1, ?s)}", -1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetNumberOfForksDoubleFunction() {
        ParallelSplitFunction.getNumberOfForksFromFunctionArgument("select * where {?s ?p ?o. filter <" + PARALLEL_SPLIT_FUNCTION.stringValue() + ">(5, ?s) filter <" + PARALLEL_SPLIT_FUNCTION.stringValue() + ">(3, ?s)}", -1);
    }

    @Test
    public void testGetNumberOfForksDoubleMatchingFunction() {
        assertEquals(5, ParallelSplitFunction.getNumberOfForksFromFunctionArgument("select * where {?s ?p ?o. filter <" + PARALLEL_SPLIT_FUNCTION.stringValue() + ">(5, ?s) filter <" + PARALLEL_SPLIT_FUNCTION.stringValue() + ">(5, ?s) filter <http://whatever/function>()}", -1));
    }

    @Test
    public void testGetNumberOfForksFromUpdate() {
        String query = "insert {?s ?p ?o} where {?s ?p ?o. filter <" + PARALLEL_SPLIT_FUNCTION.stringValue() + ">(5, ?s)};"
            + "clear all;"
            + "delete {?s ?p ?o} where {?s ?p ?o. filter <" + PARALLEL_SPLIT_FUNCTION.stringValue() + ">(8, ?s)}";
        assertEquals(5, ParallelSplitFunction.getNumberOfForksFromFunctionArgument(query, 0));
        assertEquals(0, ParallelSplitFunction.getNumberOfForksFromFunctionArgument(query, 1));
        assertEquals(8, ParallelSplitFunction.getNumberOfForksFromFunctionArgument(query, 2));
        assertEquals(0, ParallelSplitFunction.getNumberOfForksFromFunctionArgument(query, 3));
    }

}
