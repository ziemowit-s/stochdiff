package org.textensor.stochdiff.numeric.grid;

import java.util.Collection;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.PriorityQueue;

import org.textensor.stochdiff.numeric.math.RandomGenerator;
import org.textensor.stochdiff.numeric.math.MersenneTwister;
import org.textensor.stochdiff.numeric.chem.ReactionTable;
import static org.textensor.stochdiff.numeric.chem.ReactionTable.getReactionSignature;
import org.textensor.util.ArrayUtil;
import org.textensor.util.inst;
import org.textensor.stochdiff.numeric.grid.GridCalc;
import org.textensor.stochdiff.numeric.morph.VolumeGrid;
import static org.textensor.stochdiff.numeric.grid.GridCalc.intlog;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

public class NextEventQueue {
    static final Logger log = LogManager.getLogger(NextEventQueue.class);

    public interface Node {
        int index();
        void setIndex(int index);
        double time();
    }

    public class PriorityTree<T extends Node> {
        T[] nodes;

        protected T child(T a, int which) {
            assert which < 2;
            int ch = (a.index()+1)*2 - 1 + which;
            return ch < this.nodes.length ? this.nodes[ch] : null;
        }

        protected T parent(T a) {
            int ch = (a.index()+1)/2 - 1;
            if (ch < 0)
                return null;
            return this.nodes[ch];
        }

        protected T littlestChild(T a) {
            T child = this.child(a, 0);
            if (child == null)
                return null;
            T child2 = this.child(a, 1);
            if (child2 == null)
                return child;
            if (child.time() <= child2.time())
                return child;
            return child2;
        }

        void swap(T a, T b) {
            assert this.parent(b) == a;
            int ai = a.index(),
                bi = b.index();
            this.nodes[ai] = b;
            this.nodes[bi] = a;
            a.setIndex(bi);
            b.setIndex(ai);
        }

        void build(T[] nodes) {
            Comparator<T> c = new Comparator<T>() {
                @Override
                public int compare(T a, T b) {
                    return Double.compare(a.time(), b.time());
                }
            };
            Arrays.sort(nodes, c);

            for (int i = 0; i < nodes.length; i++)
                nodes[i].setIndex(i);

            this.nodes = nodes;
        }

        T first() {
            return this.nodes[0];
        }

        void update(T node) {
            assert node != null;
            T parent = this.parent(node);
            log.debug("updating position of {} t={} parent={}", node, node.time(), parent);

            if (parent != null && parent.time() > node.time()) {
                this.swap(parent, node); // original parent first
                this.update(node);
            } else {
                T littlest = this.littlestChild(node);
                log.debug("littlest Child is {} t={}", littlest,
                          littlest != null ? littlest.time() : "-");
                if (littlest != null && node.time() > littlest.time()) {
                    this.swap(node, littlest); // original parent first
                    this.update(node);
                }
            }
        }
    }

    public abstract class NextEvent implements Node {
        int index;

        final private int element;
        final String signature;

        protected double time;
        double propensity;

        NextEvent(int element, String signature) {
            this.element = element;
            this.signature = signature;
        }

        @Override
        public int index() {
            return this.index;
        }

        @Override
        public void setIndex(int index) {
            this.index = index;
        }

        @Override
        public double time() {
            return this.time;
        }

        /**
         * Add and remove particles as appropriate for this event type.
         */
        abstract void execute(int[] reactionEvents,
                              int[][] diffusionEvents,
                              int[] stimulationEvents);

        /**
         * Calculate propensity of this event.
         */
        public abstract double _propensity();

        /**
         * Reculculate propensity. Return old.
         */
        int[] old_pop;
        double _update_propensity() {
            double old = this.propensity;
            int[] pop = this.reactantPopulation();
            this.propensity = this._propensity();
            log.debug("{}: propensity changed {} → {} (n={} → {})",
                      this, old, this.propensity, old_pop, pop);
            assert this.propensity == 0 || this.propensity != old;
            this.old_pop = pop;
            return old;
        }

        private int[] reactantPopulation() {
            int[] react = this.reactants();
            int[] pop = new int[react.length];
            for (int i = 0; i < react.length; i++)
                pop[i] = particles[this.element()][react[i]];
            return pop;
        }

        void update(double current) {
            this._update_propensity();
            this.time = current + random.exponential(this.propensity);
            log.debug("{}: time changed {} → {}", this, current, this.time);
            log.debug("{} dependent: {}", this, this.dependent);
            queue.update(this);

            for (NextEvent dep: this.dependent) {
                double old = dep._update_propensity();
                if (!Double.isInfinite(dep.time))
                    dep.time = (dep.time - current) * old / dep.propensity + current;
                else
                    dep.time = current + random.exponential(dep.propensity);
                queue.update(dep);
            }
        }

        Collection<NextEvent> dependent = inst.newArrayList();

        public abstract int[] reactants();

        public int element() {
            return element;
        }

        public abstract void addDependent(NextEvent[] coll);
    }

    public class NextDiffusion extends NextEvent {
        final int element2, index2;
        final int sp;
        final double fdiff;
        final private int[] reactants;

        /**
         * @param element index of source element in particles array
         * @param element2 index of target element in particles array
         * @param index2 number of the target neighbor in list of neighbors
         * @param specie specie index
         * @param signature string to use in reporting
         * @param fdiff diffusion constant
         */
        NextDiffusion(int element, int element2, int index2,
                      int sp, String signature, double fdiff) {
            super(element, signature);
            this.element2 = element2;
            this.index2 = index2;
            this.sp = sp;
            this.reactants = new int[] {sp};
            this.fdiff = fdiff;

            this.propensity = this._propensity();
            this.time = random.exponential(this.propensity);

            log.debug("Created {}: t={}", this, this.time);
        }

        void execute(int[] reactionEvents,
                     int[][] diffusionEvents,
                     int[] stimulationEvents) {
            particles[this.element()][this.sp] -= 1;
            particles[this.element2][this.sp] += 1;

            assert particles[this.element()][this.sp] >= 0;

            diffusionEvents[this.sp][this.index2] += 1;
        }

        @Override
        public double _propensity() {
            return this.fdiff * particles[this.element()][this.sp];
        }

        public int[] reactants() {
            return this.reactants;
        }

        public void addDependent(NextEvent[] coll) {
            ArrayList<NextEvent> d = inst.newArrayList();
            for (NextEvent e: coll)
                if (e != this &&
                    (e.element() == this.element() ||
                     e.element() == this.element2) &&
                    ArrayUtil.intersect(e.reactants(), this.sp))
                    this.dependent.add(e);
        }

        @Override
        public String toString() {
            return String.format("%s el. %d→%d %s",
                                 getClass().getSimpleName(),
                                 element(), element2, signature);
        }
    }

    public class NextReaction extends NextEvent {
        final int[]
            reactants, products,
            reactant_stochiometry, product_stochiometry,
            reactant_powers;
        final int index;
        final double rate, volume;

        /**
         * @param index the index of this reaction in reactions array
         * @param element voxel number
         * @param reactants indices of reactants
         * @param products indices of products
         * @param reactant_stochiometry stochiometry of reactants
         * @param product_stochiometry stochiometry of products
         * @param reactant_powers coefficients of reactants
         * @param signature string to use in logging
         * @param rate rate of reaction
         * @param volume voxel volume
         */
        NextReaction(int index, int element, int[] reactants, int[] products,
                     int[] reactant_stochiometry, int[] product_stochiometry,
                     int[] reactant_powers, String signature,
                     double rate, double volume) {
            super(element, signature);
            this.index = index;
            this.reactants = reactants;
            this.products = products;
            this.reactant_stochiometry = reactant_stochiometry;
            this.product_stochiometry = product_stochiometry;
            this.reactant_powers = reactant_powers;

            this.rate = rate;
            this.volume = volume;

            this.propensity = this._propensity();
            this.time = random.exponential(this.propensity);

            log.debug("Created {} rate={} vol={} time={}", this,
                      this.rate, this.volume, this.time);
            assert this.time > 0;
        }

        public int[] reactants() {
            return this.reactants;
        }

        public void addDependent(NextEvent[] coll) {
            for (NextEvent e: coll) {
                if (e != this &&
                    e.element() == this.element() &&
                    (ArrayUtil.intersect(e.reactants(), this.reactants) ||
                     ArrayUtil.intersect(e.reactants(), this.products)))
                    this.dependent.add(e);
            }
        }

        void execute(int[] reactionEvents,
                     int[][] diffusionEvents,
                     int[] stimulationEvents) {
            for (int i = 0; i < this.reactants.length; i++) {
                particles[this.element()][this.reactants[i]] -= this.reactant_stochiometry[i];
                assert particles[this.element()][this.reactants[i]] >= 0;
            }
            for (int i = 0; i < this.products.length; i++)
                particles[this.element()][this.products[i]] += this.product_stochiometry[i];
            reactionEvents[this.index] += 1;
        }

        @Override
        public double _propensity() {
            double prop = ExactStochasticGridCalc.calculatePropensity(this.reactants, this.products,
                                                                      this.reactant_stochiometry,
                                                                      this.product_stochiometry,
                                                                      this.reactant_powers,
                                                                      this.rate,
                                                                      this.volume,
                                                                      particles[this.element()]);
            log.debug("{}: rate={} vol={} propensity={}",
                      this, this.rate, this.volume, prop);

            return prop;
        }

        @Override
        public String toString() {
            return String.format("%s el. %d %s",
                                 getClass().getSimpleName(),
                                 element(),
                                 signature);
        }
    }

    final RandomGenerator random = new MersenneTwister();

    /**
     * Particle counts: [voxels × species]
     */
    final int[][] particles;
    final PriorityTree<NextEvent> queue = new PriorityTree<NextEvent>();

    protected NextEventQueue(int[][] particles) {
        this.particles = particles;
    }

    ArrayList<NextDiffusion> createDiffusions(VolumeGrid grid, ReactionTable rtab) {
        int[][] neighbors = grid.getPerElementNeighbors();
        double[][] couplings = grid.getPerElementCouplingConstants();
        double[] fdiff = rtab.getDiffusionConstants();
        String[] species = rtab.getSpecieIDs();

        ArrayList<NextDiffusion> ans = inst.newArrayList(3 * neighbors.length);

        for (int el = 0; el < neighbors.length; el++)
            for (int j = 0; j < neighbors[el].length; j++) {
                int el2 = neighbors[el][j];
                double cc = couplings[el][j];
                for (int sp = 0; sp < fdiff.length; sp++)
                    ans.add(new NextDiffusion(el, el2, j, sp, species[sp],
                                              fdiff[sp] * cc));
            }

        log.info("Created {} diffusion events", ans.size());

        return ans;
    }

    ArrayList<NextReaction> createReactions(VolumeGrid grid, ReactionTable rtab) {
        double[] volumes = grid.getElementVolumes();
        int n = rtab.getNReaction() * volumes.length;
        int[][]
            RI = rtab.getReactantIndices(),
            PI = rtab.getProductIndices(),
            RS = rtab.getReactantStochiometry(),
            PS = rtab.getProductStochiometry(),
            RP = rtab.getReactantPowers();
        String[] species = rtab.getSpecieIDs();

        ArrayList<NextReaction> ans = inst.newArrayList(RI.length * volumes.length);

        for (int r = 0; r < rtab.getNReaction(); r++) {
            int[] ri = RI[r], pi = PI[r], rs = RS[r], ps = PS[r], rp = RP[r];
            double rate = rtab.getRates()[r];

            for (int el = 0; el < volumes.length; el++) {
                String signature = getReactionSignature(ri, rs, pi, ps, species);
                ans.add(new NextReaction(r, el, ri, pi, rs, ps, rp,
                                         signature,
                                         rate, volumes[el]));
            }
        }

        log.info("Created {} reaction events", ans.size());

        return ans;
    }

    public static NextEventQueue create(int[][] particles,
                                        VolumeGrid grid, ReactionTable rtab) {
        NextEventQueue obj = new NextEventQueue(particles);

        NextEvent[] d = obj.createDiffusions(grid, rtab).toArray(new NextEvent[0]),
                    r = obj.createReactions(grid, rtab).toArray(new NextEvent[0]);
        NextEvent[] e = Arrays.copyOf(d, d.length + r.length);
        System.arraycopy(r, 0, e, d.length, r.length);

        obj.queue.build(e);

        for (NextEvent ev: e)
            ev.addDependent(e);

        log.info("Events at the beginning:");
        for (int i = 0; i < e.length && i < 50; i++)
            log.info("{} → {} prop={} t={}", i, e[i], e[i].propensity, e[i].time());

        return obj;
    }

    /**
     * Execute an event if the next event is before tstop.
     *
     * @returns Time of event.
     */
    public double advance(double time, double tstop,
                          int[][] reactionEvents,
                          int[][][] diffusionEvents,
                          int[][] stimulationEvents) {
        NextEvent ev = this.queue.first();
        assert ev != null;
        double now = ev.time;

        log.debug("Advanced {}→{},{} with event {}", time, now, tstop, ev);

        if (now > tstop)
            return tstop;

        ev.execute(reactionEvents[ev.element()],
                   diffusionEvents[ev.element()],
                   stimulationEvents[ev.element()]);
        ev.update(now);
        return now;
    }
}
