/* 
 * Kodkod -- Copyright (c) 2005-present, Emina Torlak
 * Pardinus -- Copyright (c) 2013-present, Nuno Macedo, INESC TEC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package kodkod.instance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import kodkod.ast.Decls;
import kodkod.ast.Expression;
import kodkod.ast.Formula;
import kodkod.ast.Relation;
import kodkod.ast.VarRelation;
import kodkod.ast.Variable;
import kodkod.engine.Evaluator;
import kodkod.engine.ltl2fol.TemporalBoundsExpander;
import kodkod.engine.ltl2fol.TemporalTranslator;
import kodkod.util.ints.IndexedEntry;

/**
 * Represents a temporal instance of a temporal relational problem containing
 * {@link kodkod.ast.VarRelation variable relations} in the
 * {@link kodkod.instance.TemporalBounds temporal bounds}.
 * 
 * As of Pardinus 1.2, a looping state is always assumed to exist (i.e., they
 * always represent an infinite path).
 * 
 * A temporal instance has two interpretations. One is essentially a set of
 * states and a looping state, the other a static {@link Instance} under a state
 * idiom, so that the Kodkod {@link Evaluator} can be used.
 * 
 * @author Nuno Macedo // [HASLab] temporal model finding
 */
public class TemporalInstance extends Instance {

	/** The states comprising the trace. */
	public final List<Instance> states;
	/** The looping state. */
	public final int loop;

	/**
	 * Creates a new temporal instance from a sequence of states and a looping
	 * state. Will make <code>this</code> a matching static instance so that the
	 * {@link Evaluator} can be used, with an extended universe.
	 * 
	 * As of Pardinus 1.2, loops are assumed to always exist.
	 * 
	 * @assumes 0 >= loop >= instances.length
	 * @assumes all s,s': instance | s.universe = s'.universe && s.intTuples =
	 *          s'.intTuples
	 * @param instances
	 *            the states of the temporal instance.
	 * @param loop
	 *            the looping state.
	 * @ensures this.states = instances && this.loop = loop
	 * @throws NullPointerException
	 *             instances = null
	 * @throws IllegalArgumentException
	 *             !(0 >= loop >= instances.length)
	 */
	public TemporalInstance(List<Instance> instances, int loop) {
		super(TemporalBoundsExpander.expandUniverse(instances.get(0).universe(), instances.size(), 1));
		if (loop < 0 || loop >= instances.size())
			throw new IllegalArgumentException("Looping state must be between 0 and instances.length.");
		
		Map<Relation, TupleSet> expRels = stateIdomify(this.universe(), instances, loop);
		for (Relation r : expRels.keySet())
			this.add(r, expRels.get(r));
		for(IndexedEntry<TupleSet> entry : instances.get(0).intTuples())
			this.add(entry.index(), entry.value());

		this.states = instances;
		this.loop = loop;
	}

	/**
	 * Converts a sequence of instances into a state idiom representation by
	 * appending the corresponding state atom to variable relations.
	 * 
	 * @param instances
	 *            the sequence of instances representing a trace
	 * @return the trace represented in a state idiom
	 */
	private static Map<Relation, TupleSet> stateIdomify(Universe u, List<Instance> instances, int loop) {
		assert(loop >= 0 && loop < instances.size());
		// first instances.size() atoms will be the state atoms
		Map<Relation, TupleSet> instance = new HashMap<Relation, TupleSet>();
		for (Relation r : instances.get(0).relations())
			if (r instanceof VarRelation) {
				if (instance.get(((VarRelation) r).expanded) == null)
					instance.put(((VarRelation) r).expanded, u.factory().noneOf(r.arity()+1));
				for (int i = 0; i < instances.size(); i++) {
					TupleSet ts = TemporalBoundsExpander.convertToUniv(instances.get(i).tuples(r), u);
					instance.get(((VarRelation) r).expanded).addAll(ts.product(u.factory().setOf(u.atom(i))));
				}
			} else {
				TupleSet ts = u.factory().noneOf(r.arity());
				for (Tuple t : instances.get(0).tuples(r)) {
					List<Object> lt = new ArrayList<Object>();
					for (int j = 0; j < t.arity(); j++)
						lt.add(t.atom(j));
					ts.add(u.factory().tuple(lt));
				}
				instance.put(r, ts);
			}
		instance.put(TemporalTranslator.LAST, u.factory().setOf(u.atom(instances.size() - 1)));
		instance.put(TemporalTranslator.FIRST, u.factory().setOf(u.atom(0)));
		instance.put(TemporalTranslator.LOOP, u.factory().setOf(u.atom(loop)));
		instance.put(TemporalTranslator.STATE,
				u.factory().range(u.factory().tuple(u.atom(0)), u.factory().tuple(u.atom(instances.size() - 1))));
		TupleSet nxt = u.factory().noneOf(2);
		for (int i = 0; i < instances.size() - 1; i++)
			nxt.add(u.factory().tuple(u.atom(i), u.atom(i + 1)));
		instance.put(TemporalTranslator.PREFIX, nxt);
		return instance;
	}

	/**
	 * Creates a new temporal instance from a static instance in the state idiom.
	 * The shape of the trace are retrieved from the evaluation of the
	 * {@link kodkod.engine.ltl2fol.TemporalTranslator#STATE time} relations. The
	 * original variable relations (prior to expansion) are also considered since
	 * they contain information regarding their temporal properties.
	 * 
	 * @assumes some instance.loop
	 * @param instance
	 *            the expanded static solution to the problem
	 * @param tmptrans
	 *            temporal translation information, including original variable
	 *            relations
	 * @throws IllegalArgumentException
	 *             no instance.loop
	 */
	public TemporalInstance(Instance instance, TemporalTranslator tmptrans) {
		super(instance.universe(), new HashMap<Relation, TupleSet>(instance.relationTuples()), instance.intTuples());
		Evaluator eval = new Evaluator(this);
		// evaluate last relation
		Tuple tuple_last = eval.evaluate(TemporalTranslator.LAST,0).iterator().next();
		int end = TemporalTranslator.interpretState(tuple_last);
		// evaluate loop relation
		TupleSet tupleset_loop = eval.evaluate(TemporalTranslator.LOOP,0);
		if (!tupleset_loop.iterator().hasNext())
			throw new IllegalArgumentException("Looping state must exist.");
		Tuple tuple_loop = tupleset_loop.iterator().next();
		loop = TemporalTranslator.interpretState(tuple_loop);

		states = new ArrayList<Instance>();
		// for each state, create a new instance by evaluating relations at that state
		for (int i = 0; i <= end; i++) {
			Instance inst = new Instance(instance.universe());

			for (Relation r : tmptrans.bounds.relations()) {
				TupleSet t = eval.evaluate(r, i);
				inst.add(r, t);
			}

			states.add(inst);
		}
	}

	// alternative encodings, atom reification vs some disj pattern
	private static final boolean SomeDisjPattern = false;

	/**
	 * Converts a temporal instance into a formula that exactly identifies it,
	 * encoding each state of the trace and the looping behavior. Requires that
	 * every relevant atom be reified into a singleton relation, which may be
	 * re-used between calls. Will be used between the various states of the trace.
	 * 
	 * @assumes reif != null
	 * @param reif
	 *            the previously reified atoms
	 * @throws NullPointerException
	 *             reif = null
	 * @return the formula representing <this>
	 */
	// [HASLab]
	@Override
	public Formula formulate(Map<Object, Expression> reif) {

		// reify atoms not yet reified
		Universe sta_uni = states.get(0).universe();
		for (int i = 0; i < sta_uni.size(); i++) {
			if (!reif.keySet().contains(sta_uni.atom(i))) {
				Expression r;
				if (SomeDisjPattern) {
					r = Variable.unary(sta_uni.atom(i).toString());
				} else {
					r = Relation.unary(sta_uni.atom(i).toString());
				}
				reif.put(sta_uni.atom(i), r);
			}
		}

		// create the constraint for each state
		// S0 and after (S1 and after ...)
		Formula res;
		if (states.isEmpty())
			res = Formula.TRUE;
		else
			res = states.get(states.size() - 1).formulate(reif);

		for (int i = states.size() - 2; i >= 0; i--)
			res = states.get(i).formulate(reif).and(res.next());

		// create the looping constraint
		// after^loop always (Sloop => after^(end-loop) Sloop && Sloop+1 =>
		// after^(end-loop) Sloop+1 && ...)
		Formula rei = states.get(loop).formulate(reif);
		Formula rei2 = rei;
		for (int j = loop; j < states.size(); j++)
			rei2 = rei2.next();

		Formula looping = rei.implies(rei2);
		for (int i = loop + 1; i < states.size(); i++) {
			rei = states.get(i).formulate(reif);
			rei2 = rei;
			for (int j = loop; j < states.size(); j++)
				rei2 = rei2.next();
			looping = looping.and(rei.implies(rei2));
		}
		looping = looping.always();
		for (int i = 0; i < loop; i++)
			looping = looping.next();

		res = res.and(looping);

		if (SomeDisjPattern) {
			Decls decls = null;
			Expression al = null;
			for (Expression e : reif.values()) {
				if (decls == null) {
					al = e;
					decls = ((Variable) e).oneOf(Expression.UNIV);
				} else {
					al = al.union(e);
					decls = decls.and(((Variable) e).oneOf(Expression.UNIV));
				}
			}
			res = (al.eq(Expression.UNIV)).and(res);
			res = res.forSome(decls);
		}

		return res;
	}

	/**
	 * {@inheritDoc}
	 * 
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < states.size(); i++) {
			sb.append("* state " + i);
			if (loop == i)
				sb.append(" LOOP");
			if (states.size() - 1 == i)
				sb.append(" LAST");
			sb.append("\n" + states.get(i).toString());
			sb.append("\n");
		}
		return sb.toString();
	}

}
