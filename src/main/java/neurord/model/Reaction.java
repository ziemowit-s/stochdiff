package neurord.model;

import java.util.ArrayList;
import java.util.HashMap;

import java.util.StringTokenizer;

import javax.xml.bind.annotation.*;

import neurord.numeric.chem.ReactionTable;
import static neurord.model.Specie.generateID;
import neurord.util.Settings;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

public class Reaction {
    static public final Logger log = LogManager.getLogger();

    @XmlAttribute
    public String name;

    @XmlAttribute
    public String id;

    @XmlElement(name="Reactant")
    private final ArrayList<Reactant> p_reactants = new ArrayList<>();

    @XmlElement(name="Product")
    private final ArrayList<Product> p_products = new ArrayList<>();

    private Double forwardRate;
    private Double reverseRate;

    public Double Q10;

    static final boolean reactions = Settings.getProperty("neurord.reactions",
                                                          "Allow reactions to happen",
                                                          true);
    transient private ArrayList<Specie> r_reactants;
    transient private ArrayList<Specie> r_products;

    public String getID() {
        return this.id != null ? this.id : generateID(this.getName());
    }

    protected static String formatSide(ArrayList<? extends SpecieRef> list) {
        if (list.isEmpty())
            return "nil";

        StringBuffer b = new StringBuffer();
        boolean second = false;
        for (SpecieRef r: list) {
            if (second)
                b.append("+");
            else
                second = true;
            if (r.getStochiometry() > 1)
                b.append("" + r.getStochiometry() + "×");
            b.append(r.getSpecieID());
        }
        return b.toString();
    }

    public String getName() {
        if (this.name == null)
            this.name = formatSide(this.p_reactants) + "→" + formatSide(this.p_products);
        return this.name;
    }

    public double getForwardRate() {
        if (!reactions)
            return 0;

        return this.forwardRate != null ? this.forwardRate : 0;
    }

    public double getReverseRate() {
        if (!reactions)
            return 0;

        return this.reverseRate != null ? this.reverseRate : 0;
    }

    public void add(Object obj) {
        if (obj instanceof Reactant)
            this.p_reactants.add((Reactant)obj);
        else if (obj instanceof Product)
            this.p_products.add((Product)obj);
        else
            throw new RuntimeException("cannot add " + obj);
    }

    public void resolve(HashMap<String, Specie> sphm) {
        if (this.p_reactants.isEmpty())
            log.warn("no reactants in reaction {}", name);
        this.r_reactants = parseRefs(this.p_reactants, sphm);
        this.r_products = parseRefs(this.p_products, sphm);
    }

    private ArrayList<Specie> parseRefs(ArrayList<? extends SpecieRef> asr,
                                        HashMap<String, Specie> sphm) {

        ArrayList<Specie> ret = new ArrayList<>();
        for (SpecieRef sr : asr) {
            Specie sr2 = sphm.get(sr.getSpecieID());
            if (sr2 == null)
                throw new RuntimeException
                    ("reaction " + name + " mentions unknown specie " + sr);
            ret.add(sr2);
        }
        return ret;
    }

    /**
     * Returns an array [2 x nspecies] containing species and
     * their counts (n) in reaction. This is the "fake" multiplicity,
     * which does not influence propensity, only the number of molecules
     * destroyed or produced in the reaction.
     */
    private static int[][] getIndices(ArrayList<Specie> spa,
                                      ArrayList<? extends SpecieRef> refs) {

        int n = spa.size();
        int[][] ret = new int[3][n];

        for (int i = 0; i < n; i++) {
            ret[0][i] = spa.get(i).getIndex();
            ret[1][i] = refs.get(i).getStochiometry();
            ret[2][i] = refs.get(i).getPower();
            assert ret[0][i] >= 0;
            assert ret[1][i] >= 1;
            assert ret[2][i] >= 0;
        }

        return ret;
    }

    /**
     * Returns an array [2 x nspecies] containing reactants and
     * their counts (n) in reaction. This is the "fake" multiplicity,
     * which does not influence propensity, only the number of molecules
     * destroyed or produced in the reaction.
     */
    public int[][] getReactantIndices() {
        return getIndices(this.r_reactants, this.p_reactants);
    }

    /**
     * Returns an array [2 x nspecies] containing products and
     * their counts (n) in reaction. This is the "fake" multiplicity,
     * which does not influence propensity, only the number of molecules
     * destroyed or produced in the reaction.
     */
    public int[][] getProductIndices() {
        return getIndices(this.r_products, this.p_products);
    }
}
