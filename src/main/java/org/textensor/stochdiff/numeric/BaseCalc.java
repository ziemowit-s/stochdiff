//3 5 2008: WK changed the initial value of the denominator variable in the extractGrid function from 3 to 1
//6 22 2007: WK modified the extractGrid() function to calculate the side-length of
//           each volume element (which is a square with a predefined thickness).
//6 19 2007: WK added 1 variable and 1 function to be able to output by user-specified 'region's.
//5 16 2007: WK added 4 variables and 5 functions (within <--WK ... WK-->)
//written by Robert Cannon
package org.textensor.stochdiff.numeric;

import java.util.ArrayList;
import java.util.StringTokenizer;
import java.util.Random;

import org.textensor.stochdiff.model.*;
import org.textensor.stochdiff.numeric.chem.ReactionTable;
import org.textensor.stochdiff.numeric.chem.StimulationTable;
import org.textensor.stochdiff.numeric.morph.VolumeGrid;
import org.textensor.stochdiff.numeric.grid.ResultWriter;
import org.textensor.util.inst;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

/**
 * Units: concentrations are expressed in nM and volumes in cubic microns
 * So, in these units, one Litre is 10^15 and a 1M solution is 10^9.
 * The conversion factor between concentrations and particle number is
 * therefore
 * nparticles = 6.022^23 * vol/10^15 * conc/10^9
 * ie, nparticles = 0.6022 * vol * conc
 */

public abstract class BaseCalc implements Runnable {
    static final Logger log = LogManager.getLogger(BaseCalc.class);

    // particles Per Unit Volume and Concentration
    public static final double PARTICLES_PUVC = 0.602214179;
    public static final double LN_PARTICLES_PUVC = Math.log(PARTICLES_PUVC);

    // particles per unit area and surface density
    // (is the same as PUVC - sd unit is picomoles per square metre)
    public static final double PARTICLES_PUASD = PARTICLES_PUVC;

    // converting particle numbers to concentrations
    // nanomoles per particle per unit volume
    // ie, each particle added to a cubic micron increases
    // the nanoMolar concentration this much
    public static final double NM_PER_PARTICLE_PUV = 1. / PARTICLES_PUVC;

    protected final ArrayList<ResultWriter> resultWriters = inst.newArrayList();

    public enum distribution_t {
        BINOMIAL,
        POISSON,
    }

    public enum algorithm_t {
        INDEPENDENT,
        SHARED,
        PARTICLE,
    }

    public enum output_t {
        NUMBER,
        CONCENTRATION,
    }

    protected final distribution_t distID;
    protected final algorithm_t algoID;

    final public boolean writeConcentration;

    private final int trial;
    protected SDRunWrapper wrapper;

    public BaseCalc(int trial, SDRunWrapper wrapper) {
        this.trial = trial;
        this.wrapper = wrapper;

        this.distID = wrapper.sdRun.getDistribution();
        this.algoID = wrapper.sdRun.getAlgorithm();
        this.writeConcentration =
            output_t.valueOf(wrapper.sdRun.outputQuantity) == output_t.CONCENTRATION;
        log.info("Writing particle numbers as {}s", wrapper.sdRun.outputQuantity);
    }

    public int trial() {
        return this.trial;
    }

    public SDRunWrapper getSource() {
        return this.wrapper;
    }

    private long seed = -1;
    public long getSimulationSeed() {
        if (seed == -1) {
            if (wrapper.sdRun.simulationSeed > 0)
                seed = wrapper.sdRun.simulationSeed;
            else
                seed = Math.abs(new Random().nextInt());
            log.info("Trial {}: running with simulationSeed {}", this.trial(), seed);
        }
        return seed;
    }

    public void addResultWriter(ResultWriter rw) {
        rw.init("cctdif2d"); // others....
        this.resultWriters.add(rw);
    }

    protected abstract void _run();

    @Override
    public void run() {
        try {
            this._run();
        } catch(Error e) {
            log.error("{}: failed (seed={})", this, seed);
            throw e;
        }
    }

    public void close() {
        for (ResultWriter resultWriter: this.resultWriters)
            resultWriter.close();
    }

    public abstract long getParticleCount();
}
