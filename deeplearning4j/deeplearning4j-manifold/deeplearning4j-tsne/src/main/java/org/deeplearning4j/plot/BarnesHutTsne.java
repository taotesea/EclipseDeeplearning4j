/*******************************************************************************
 * Copyright (c) 2015-2018 Skymind, Inc.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 ******************************************************************************/

package org.deeplearning4j.plot;


import org.nd4j.shade.guava.util.concurrent.AtomicDouble;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.deeplearning4j.clustering.algorithm.Distance;
import org.deeplearning4j.clustering.sptree.DataPoint;
import org.deeplearning4j.clustering.sptree.SpTree;
import org.deeplearning4j.clustering.vptree.VPTree;
import org.deeplearning4j.nn.api.Model;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.WorkspaceMode;
import org.deeplearning4j.nn.gradient.DefaultGradient;
import org.deeplearning4j.nn.gradient.Gradient;
import org.deeplearning4j.nn.workspace.LayerWorkspaceMgr;
import org.deeplearning4j.optimize.api.ConvexOptimizer;
import org.deeplearning4j.optimize.api.TrainingListener;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.memory.conf.WorkspaceConfiguration;
import org.nd4j.linalg.api.memory.enums.*;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.BooleanIndexing;
import org.nd4j.linalg.indexing.conditions.Conditions;
import org.nd4j.linalg.learning.legacy.AdaGrad;
import org.nd4j.common.primitives.Pair;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

import static org.nd4j.linalg.factory.Nd4j.*;
import static org.nd4j.linalg.ops.transforms.Transforms.pow;


/**
 * Barnes hut algorithm for TSNE, uses a dual tree approximation approach.
 * Work based on:
 * <a href="http://lvdmaaten.github.io/tsne/">http://lvdmaaten.github.io/tsne/</a>
 * For hight dimensions, it's recommended to reduce the dimension up to 50 using another method (PCA or other)
 * @author Adam Gibson
 */
@Slf4j
@Data
public class BarnesHutTsne implements Model {


    public final static String workspaceCache = "LOOP_CACHE";
    public final static String workspaceExternal = "LOOP_EXTERNAL";


    protected int maxIter = 1000;
    protected double realMin = Nd4j.EPS_THRESHOLD;
    protected double initialMomentum = 0.5;
    protected double finalMomentum = 0.8;
    protected double minGain = 1e-2;
    protected double momentum = initialMomentum;
    protected int switchMomentumIteration = 250;
    protected boolean normalize = true;
    protected boolean usePca = false;
    protected int stopLyingIteration = 250;
    protected double tolerance = 1e-5;
    protected double learningRate = 500;
    protected AdaGrad adaGrad;
    protected boolean useAdaGrad = true;
    protected double perplexity = 30;
    //protected INDArray gains,yIncs;
    protected INDArray Y;
    private int N;
    private double theta;
    private INDArray rows;
    private INDArray cols;
    private INDArray vals;
    private String simiarlityFunction = "cosinesimilarity";
    private boolean invert = true;
    private INDArray x;
    private int numDimensions = 0;
    public final static String Y_GRAD = "yIncs";
    private SpTree tree;
    private INDArray gains;
    @Setter
    private INDArray yIncs;
    private int vpTreeWorkers;
    protected transient TrainingListener trainingListener;
    protected WorkspaceMode workspaceMode;
    private Initializer initializer;

    protected final static WorkspaceConfiguration workspaceConfigurationExternal = WorkspaceConfiguration.builder()
            .initialSize(0).overallocationLimit(0.3).policyLearning(LearningPolicy.FIRST_LOOP)
            .policyReset(ResetPolicy.BLOCK_LEFT).policySpill(SpillPolicy.REALLOCATE)
            .policyAllocation(AllocationPolicy.OVERALLOCATE).build();

    protected WorkspaceConfiguration workspaceConfigurationFeedForward = WorkspaceConfiguration.builder().initialSize(0)
            .overallocationLimit(0.2).policyReset(ResetPolicy.BLOCK_LEFT)
            .policyLearning(LearningPolicy.OVER_TIME).policySpill(SpillPolicy.REALLOCATE)
            .policyAllocation(AllocationPolicy.OVERALLOCATE).build();

    public final static WorkspaceConfiguration workspaceConfigurationCache = WorkspaceConfiguration.builder()
            .overallocationLimit(0.2).policyReset(ResetPolicy.BLOCK_LEFT).cyclesBeforeInitialization(3)
            .policyMirroring(MirroringPolicy.FULL).policySpill(SpillPolicy.REALLOCATE)
            .policyLearning(LearningPolicy.OVER_TIME).build();


    public BarnesHutTsne(int numDimensions, String simiarlityFunction, double theta, boolean invert, int maxIter,
                         double realMin, double initialMomentum, double finalMomentum, double momentum,
                         int switchMomentumIteration, boolean normalize, int stopLyingIteration, double tolerance,
                         double learningRate, boolean useAdaGrad, double perplexity, TrainingListener TrainingListener,
                         double minGain,int vpTreeWorkers) {
        this(numDimensions, simiarlityFunction, theta, invert, maxIter, realMin, initialMomentum, finalMomentum,
                momentum, switchMomentumIteration, normalize, stopLyingIteration, tolerance, learningRate,
                useAdaGrad, perplexity, TrainingListener, minGain, vpTreeWorkers, WorkspaceMode.NONE, null);
    }

    public BarnesHutTsne(int numDimensions, String simiarlityFunction, double theta, boolean invert, int maxIter,
                         double realMin, double initialMomentum, double finalMomentum, double momentum,
                         int switchMomentumIteration, boolean normalize, int stopLyingIteration, double tolerance,
                         double learningRate, boolean useAdaGrad, double perplexity, TrainingListener TrainingListener,
                         double minGain,int vpTreeWorkers, WorkspaceMode workspaceMode, INDArray staticInput) {
        this.maxIter = maxIter;
        this.realMin = realMin;
        this.initialMomentum = initialMomentum;
        this.finalMomentum = finalMomentum;
        this.momentum = momentum;
        this.normalize = normalize;
        this.useAdaGrad = useAdaGrad;
        this.stopLyingIteration = stopLyingIteration;
        this.learningRate = learningRate;
        this.switchMomentumIteration = switchMomentumIteration;
        this.tolerance = tolerance;
        this.perplexity = perplexity;
        this.minGain = minGain;
        this.numDimensions = numDimensions;
        this.simiarlityFunction = simiarlityFunction;
        this.theta = theta;
        this.trainingListener = TrainingListener;
        this.invert = invert;
        this.vpTreeWorkers = vpTreeWorkers;
        this.workspaceMode = workspaceMode;
        if(this.workspaceMode == null)
            this.workspaceMode = WorkspaceMode.NONE;
        initializer = (staticInput != null) ? new Initializer(staticInput) : new Initializer();
    }


    public String getSimiarlityFunction() {
        return simiarlityFunction;
    }

    public void setSimiarlityFunction(String simiarlityFunction) {
        this.simiarlityFunction = simiarlityFunction;
    }

    public boolean isInvert() {
        return invert;
    }

    public void setInvert(boolean invert) {
        this.invert = invert;
    }

    public double getTheta() {
        return theta;
    }

    public double getPerplexity() {
        return perplexity;
    }

    public int getNumDimensions() {
        return numDimensions;
    }

    public void setNumDimensions(int numDimensions) {
        this.numDimensions = numDimensions;
    }

    /**
     * Convert data to probability
     * co-occurrences (aka calculating the kernel)
     * @param d the data to convert
     * @param perplexity the perplexity of the model
     * @return the probabilities of co-occurrence
     */
    public INDArray computeGaussianPerplexity(final INDArray d, double perplexity) {
        N = d.rows();

        final int k = (int) (3 * perplexity);
        if (N - 1 < 3 * perplexity)
            throw new IllegalStateException("Perplexity " + perplexity + "is too large for number of samples " + N);


        rows = zeros(DataType.INT, 1, N + 1);
        cols = zeros(DataType.INT, 1, N * k);
        vals = zeros(d.dataType(),  N * k);

        for (int n = 0; n < N; n++)
            rows.putScalar(n + 1, rows.getDouble(n) + k);

        final double enthropy = Math.log(perplexity);
        VPTree tree = new VPTree(d, simiarlityFunction, vpTreeWorkers,invert);

        /*MemoryWorkspace workspace =
                workspaceMode == WorkspaceMode.NONE ? new DummyWorkspace()
                        : Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread(
                        workspaceConfigurationExternal,
                        workspaceExternal);
        try (MemoryWorkspace ws = workspace.notifyScopeEntered())*/ {
            log.info("Calculating probabilities of data similarities...");
            for (int i = 0; i < N; i++) {
                if (i % 500 == 0)
                    log.info("Handled " + i + " records");

                double betaMin = -Double.MAX_VALUE;
                double betaMax = Double.MAX_VALUE;
                List<DataPoint> results = new ArrayList<>();
                List<Double> distances = new ArrayList<>();
                tree.search(d.getRow(i), k + 1, results, distances, false, true);
                double betas = 1.0;

                if(results.size() == 0){
                    throw new IllegalStateException("Search returned no values for vector " + i +
                            " - similarity \"" + simiarlityFunction + "\" may not be defined (for example, vector is" +
                            " all zeros with cosine similarity)");
                }

                Double[] dists = new Double[distances.size()];
                distances.toArray(dists);
                INDArray cArr = Nd4j.createFromArray(dists).castTo(d.dataType()); //VPTree.buildFromData(results);

                INDArray currP = null;
                int tries = 0;
                boolean found = false;
                //binary search
                while (!found && tries < 200) {
                    Pair<INDArray, Double> pair = computeGaussianKernel(cArr, betas, k);
                    currP = pair.getFirst();
                    double hDiff = pair.getSecond() - enthropy;

                    if (hDiff < tolerance && -hDiff < tolerance)
                        found = true;
                    else {
                        if (hDiff > 0) {
                            betaMin = betas;

                            if (betaMax == Double.MAX_VALUE || betaMax == -Double.MAX_VALUE)
                                betas *= 2;
                            else
                                betas = (betas + betaMax) / 2.0;
                        } else {
                            betaMax = betas;
                            if (betaMin == -Double.MAX_VALUE || betaMin == Double.MAX_VALUE)
                                betas /= 2.0;
                            else
                                betas = (betas + betaMin) / 2.0;
                        }

                        tries++;
                    }
                }

                currP.divi(currP.sumNumber().doubleValue() + Double.MIN_VALUE);
                INDArray indices = Nd4j.create(1, k + 1);
                for (int j = 0; j < indices.length(); j++) {
                    if (j >= results.size())
                        break;
                    indices.putScalar(j, results.get(j).getIndex());
                }

                for (int l = 0; l < k; l++) {
                    cols.putScalar(rows.getInt(i) + l, indices.getDouble(l + 1));
                    vals.putScalar(rows.getInt(i) + l, currP.getDouble(l));
                }
            }
        }
        return vals;
    }

    @Override
    public INDArray input() {
        return x;
    }

    @Override
    public ConvexOptimizer getOptimizer() {
        return null;
    }

    @Override
    public INDArray getParam(String param) {
        return null;
    }

    @Override
    public void addListeners(TrainingListener... listener) {
        // no-op
    }

    @Override
    public Map<String, INDArray> paramTable() {
        return null;
    }

    @Override
    public Map<String, INDArray> paramTable(boolean backprapParamsOnly) {
        return null;
    }

    @Override
    public void setParamTable(Map<String, INDArray> paramTable) {

    }

    @Override
    public void setParam(String key, INDArray val) {

    }

    @Override
    public void clear() {}

    @Override
    public void applyConstraints(int iteration, int epoch) {
        //No op
    }

    /* compute the gradient given the current solution, the probabilities and the constant */
    protected Pair<Double, INDArray> gradient(INDArray p) {
        throw new UnsupportedOperationException();
    }


    @Data
    @AllArgsConstructor
    static class SymResult {
        INDArray rows;
        INDArray cols;
        INDArray vals;
    }

    /**
     * Symmetrize the value matrix
     * @param rowP
     * @param colP
     * @param valP
     * @return
     */
    public SymResult symmetrized(INDArray rowP, INDArray colP, INDArray valP) {
        INDArray rowCounts = Nd4j.create(DataType.INT, N);

        /*MemoryWorkspace workspace =
                workspaceMode == WorkspaceMode.NONE ? new DummyWorkspace()
                        : Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread(
                        workspaceConfigurationExternal,
                        workspaceExternal);

        try (MemoryWorkspace ws = workspace.notifyScopeEntered())*/ {
            for (int n = 0; n < N; n++) {
                int begin = rowP.getInt(n);
                int end = rowP.getInt(n + 1);
                for (int i = begin; i < end; i++) {
                    boolean present = false;
                    for (int m = rowP.getInt(colP.getInt(i)); m < rowP.getInt(colP.getInt(i) + 1); m++)
                        if (colP.getInt(m) == n) {
                            present = true;
                        }

                    if (present)
                        rowCounts.putScalar(n, rowCounts.getInt(n) + 1);

                    else {
                        rowCounts.putScalar(n, rowCounts.getInt(n) + 1);
                        rowCounts.putScalar(colP.getInt(i), rowCounts.getInt(colP.getInt(i)) + 1);
                    }
                }
            }

            int numElements = rowCounts.sumNumber().intValue();
            INDArray offset = Nd4j.create(DataType.INT, N);
            INDArray symRowP = Nd4j.zeros(DataType.INT, N + 1);
            INDArray symColP = Nd4j.create(DataType.INT, numElements);
            INDArray symValP = Nd4j.create(valP.dataType(), numElements);

            for (int n = 0; n < N; n++)
                symRowP.putScalar(n + 1, symRowP.getInt(n) + rowCounts.getInt(n));

            for (int n = 0; n < N; n++) {
                for (int i = rowP.getInt(n); i < rowP.getInt(n + 1); i++) {
                    boolean present = false;
                    for (int m = rowP.getInt(colP.getInt(i)); m < rowP.getInt(colP.getInt(i)+1); m++) {
                        if (colP.getInt(m) == n) {
                            present = true;
                            if (n <= colP.getInt(i)) {
                                // make sure we do not add elements twice
                                symColP.putScalar(symRowP.getInt(n) + offset.getInt(n), colP.getInt(i));
                                symColP.putScalar(symRowP.getInt(colP.getInt(i)) + offset.getInt(colP.getInt(i)), n);
                                symValP.putScalar(symRowP.getInt(n) + offset.getInt(n),
                                        valP.getDouble(i) + valP.getDouble(m));
                                symValP.putScalar(symRowP.getInt(colP.getInt(i)) + offset.getInt(colP.getInt(i)),
                                        valP.getDouble(i) + valP.getDouble(m));
                            }
                        }
                    }

                    // If (colP[i], n) is not present, there is no addition involved
                    if (!present) {
                        int colPI = colP.getInt(i);
                        symColP.putScalar(symRowP.getInt(n) + offset.getInt(n), colPI);
                        symColP.putScalar(symRowP.getInt(colP.getInt(i)) + offset.getInt(colPI), n);
                        symValP.putScalar(symRowP.getInt(n) + offset.getInt(n), valP.getDouble(i));
                        symValP.putScalar(symRowP.getInt(colPI) + offset.getInt(colPI), valP.getDouble(i));
                    }

                    // Update offsets
                    if (!present || (present && n <= colP.getInt(i))) {
                        offset.putScalar(n, offset.getInt(n) + 1);
                        int colPI = colP.getInt(i);
                        if (colPI != n)
                            offset.putScalar(colPI, offset.getInt(colPI) + 1);
                    }
                }
            }

            // Divide the result by two
            symValP.divi(2.0D);
            return new SymResult(symRowP, symColP, symValP);

        }


    }

    /**
     * Computes a gaussian kernel
     * given a vector of squared distance distances
     *
     * @param distances
     * @param beta
     * @return
     */
    public Pair<INDArray, Double> computeGaussianKernel(INDArray distances, double beta, int k) {
        // Compute Gaussian kernel row
        INDArray currP = Nd4j.create(distances.dataType(), k);
        for (int m = 0; m < k; m++) {
            currP.putScalar(m, Math.exp(-beta * distances.getDouble(m + 1)));
        }

        double sum = currP.sumNumber().doubleValue() + Double.MIN_VALUE;
        double h = 0.0;
        for (int m = 0; m < k; m++)
            h += beta * (distances.getDouble(m + 1) * currP.getDouble(m));

        h = (h / sum) + Math.log(sum);

        return new Pair<>(currP, h);
    }


    /**
     * Init the model
     */
    @Override
    public void init() {

    }

    /**
     * Set the trainingListeners for the ComputationGraph (and all layers in the network)
     *
     * @param listeners
     */
    @Override
    public void setListeners(Collection<org.deeplearning4j.optimize.api.TrainingListener> listeners) {

    }

    /**
     * Set the trainingListeners for the ComputationGraph (and all layers in the network)
     *
     * @param listeners
     */
    @Override
    public void setListeners(TrainingListener... listeners) {

    }

    private int calculateOutputLength() {
        int ret = 0;

        INDArray rowCounts = Nd4j.create(N);
        for (int n = 0; n < N; n++) {
            int begin = rows.getInt(n);
            int end = rows.getInt(n + 1);
            for (int i = begin; i < end; i++) {
                boolean present = false;
                for (int m = rows.getInt(cols.getInt(i)); m < rows.getInt(cols.getInt(i) + 1); m++) {
                    if (cols.getInt(m) == n) {
                        present = true;
                    }
                }
                if (present)
                    rowCounts.putScalar(n, rowCounts.getDouble(n) + 1);

                else {
                    rowCounts.putScalar(n, rowCounts.getDouble(n) + 1);
                    rowCounts.putScalar(cols.getInt(i), rowCounts.getDouble(cols.getInt(i)) + 1);
                }
            }
        }
        ret = rowCounts.sum(Integer.MAX_VALUE).getInt(0);
        return ret;
    }

    public class Initializer {

        private INDArray staticData;

        public Initializer() {}

        public Initializer(INDArray input) {
            this.staticData = input;
        }

        public INDArray initData() {
            if (staticData != null)
                return staticData.dup();
            return randn(x.dataType(), x.rows(), numDimensions).muli(1e-3f);
        }
    }

    public static void zeroMean(INDArray input) {
        INDArray means = input.mean(0);
        input.subiRowVector(means);
    }

    @Override
    public void fit() {
        if (theta == 0.0) {
            log.debug("theta == 0, using decomposed version, might be slow");
            Tsne decomposedTsne = new Tsne(maxIter, realMin, initialMomentum, finalMomentum, minGain, momentum,
                    switchMomentumIteration, normalize, usePca, stopLyingIteration, tolerance, learningRate,
                    useAdaGrad, perplexity);
            Y = decomposedTsne.calculate(x, numDimensions, perplexity);
        } else {
            //output
            if (Y == null) {
                Y = initializer.initData();
            }

            /*MemoryWorkspace workspace =
                    workspaceMode == WorkspaceMode.NONE ? new DummyWorkspace()
                            : Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread(
                            workspaceConfigurationExternal,
                            workspaceExternal);


            try (MemoryWorkspace ws = workspace.notifyScopeEntered())*/ {

                x.divi(x.maxNumber());

                computeGaussianPerplexity(x, perplexity);
                /*INDArray outRows = Nd4j.create(new int[]{rows.rows(), rows.columns()}, DataType.INT);
                BarnesHutSymmetrize op = new BarnesHutSymmetrize(rows, cols, vals, N, outRows);
                Nd4j.getExecutioner().exec(op);
                INDArray output = op.getSymmetrizedValues();
                INDArray outCols = op.getSymmetrizedCols();
                vals = output.divi(vals.sum(Integer.MAX_VALUE));
                rows = outRows;
                cols = outCols;*/

                SymResult result = symmetrized(rows, cols, vals);
                vals = result.vals.divi(result.vals.sumNumber().doubleValue());
                rows = result.rows;
                cols = result.cols;
                //lie about gradient
                vals.muli(12);
                for (int i = 0; i < maxIter; i++) {
                    step(vals, i);
                    zeroMean(Y);
                    if (i == switchMomentumIteration)
                        momentum = finalMomentum;
                    if (i == stopLyingIteration)
                        vals.divi(12);


                    if (trainingListener != null) {
                        trainingListener.iterationDone(this, i, 0);
                    }
                }
            }
        }
    }

    @Override
    public void update(Gradient gradient) {
    }

    /**
     * An individual iteration
     * @param p the probabilities that certain points
     *          are near each other
     * @param i the iteration (primarily for debugging purposes)
     */
    public void step(INDArray p, int i) {
        update(gradient().getGradientFor(Y_GRAD), Y_GRAD);
    }

    static double sign_tsne(double x) { return (x == .0 ? .0 : (x < .0 ? -1.0 : 1.0)); }


    @Override
    public void update(INDArray gradient, String paramType) {

        /*MemoryWorkspace workspace =
                workspaceMode == WorkspaceMode.NONE ? new DummyWorkspace()
                        : Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread(
                        workspaceConfigurationExternal,
                        workspaceExternal);

        try (MemoryWorkspace ws = workspace.notifyScopeEntered())*/ {

            INDArray yGrads = gradient;
;            if (gains == null)
                gains = Y.ulike().assign(1.0);

            //Nd4j.getExecutioner().exec(new BarnesHutGains(gains, gains, yGrads, yIncs));
            // Copied from Reference
            for (int i = 0; i < yGrads.rows(); ++i) {
                for (int  j = 0; j < yGrads.columns(); ++j) {
                    if (sign_tsne(yGrads.getDouble(i,j)) == sign_tsne(yIncs.getDouble(i,j))) {
                        gains.putScalar(new int[]{i,j}, gains.getDouble(i,j)*0.8);
                    }
                    else {
                        gains.putScalar(new int[]{i,j}, gains.getDouble(i,j)+0.2);
                    }
                }
            }
            BooleanIndexing.replaceWhere(gains, minGain, Conditions.lessThan(minGain));

            Y.addi(yIncs);
            INDArray gradChange = gains.mul(yGrads);

            if (useAdaGrad) {
                if (adaGrad == null) {
                    adaGrad = new AdaGrad(gradient.shape(), learningRate);
                    adaGrad.setStateViewArray(Nd4j.zeros(gradient.shape()).reshape(1, gradChange.length()),
                            gradChange.shape(), gradient.ordering(), true);
                }

                gradChange = adaGrad.getGradient(gradChange, 0);

            } else {
                gradChange.muli(learningRate);
            }
            yIncs.muli(momentum).subi(gradChange);
        }
    }


    /**
     * Save the model as a file with a csv format, adding the label as the last column.
     * @param labels
     * @param path the path to write
     * @throws IOException
     */
    public void saveAsFile(List<String> labels, String path) throws IOException {
        try (BufferedWriter write = new BufferedWriter(new FileWriter(new File(path)))) {
            for (int i = 0; i < Y.rows(); i++) {
                if (i >= labels.size())
                    break;
                String word = labels.get(i);
                if (word == null)
                    continue;
                StringBuilder sb = new StringBuilder();
                INDArray wordVector = Y.getRow(i);
                for (int j = 0; j < wordVector.length(); j++) {
                    sb.append(wordVector.getDouble(j));
                    if (j < wordVector.length() - 1)
                        sb.append(",");
                }

                sb.append(",");
                sb.append(word);
                sb.append("\n");
                write.write(sb.toString());

            }
            write.flush();
        }
    }

    public void saveAsFile(String path) throws IOException {
        try (BufferedWriter write = new BufferedWriter(new FileWriter(new File(path)))) {
            for (int i = 0; i < Y.rows(); i++) {
                StringBuilder sb = new StringBuilder();
                INDArray wordVector = Y.getRow(i);
                for (int j = 0; j < wordVector.length(); j++) {
                    sb.append(wordVector.getDouble(j));
                    if (j < wordVector.length() - 1)
                        sb.append(",");
                }
                sb.append("\n");
                write.write(sb.toString());
            }
            write.flush();
        }
    }
    /**
     * Plot tsne
     *
     * @param matrix the matrix to plot
     * @param nDims  the number
     * @param labels
     * @param path   the path to write
     * @throws IOException
     * @deprecated use {@link #fit(INDArray)} and {@link #saveAsFile(List, String)} instead.
     */
    @Deprecated
    public void plot(INDArray matrix, int nDims, List<String> labels, String path) throws IOException {
        fit(matrix, nDims);
        saveAsFile(labels, path);
    }


    @Override
    public double score() {

        /*MemoryWorkspace workspace =
                workspaceMode == WorkspaceMode.NONE ? new DummyWorkspace()
                        : Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread(
                        workspaceConfigurationExternal,
                        workspaceExternal);


        try (MemoryWorkspace ws = workspace.notifyScopeEntered())*/ {


            // Get estimate of normalization term
            INDArray buff = Nd4j.create(numDimensions);
            AtomicDouble sum_Q = new AtomicDouble(0.0);
            for (int n = 0; n < N; n++)
                tree.computeNonEdgeForces(n, theta, buff, sum_Q);

            // Loop over all edges to compute t-SNE error
            double C = .0;
            INDArray linear = Y;
            for (int n = 0; n < N; n++) {
                int begin = rows.getInt(n);
                int end = rows.getInt(n + 1);
                int ind1 = n;
                for (int i = begin; i < end; i++) {
                    int ind2 = cols.getInt(i);
                    linear.slice(ind1).subi(linear.slice(ind2), buff);

                    double Q = pow(buff, 2).sumNumber().doubleValue();
                    Q = (1.0 / (1.0 + Q)) / sum_Q.doubleValue();
                    C += vals.getDouble(i) * Math.log(vals.getDouble(i) + Nd4j.EPS_THRESHOLD)
                            / (Q + Nd4j.EPS_THRESHOLD);
                }
            }

            return C;

        }

    }

    @Override
    public void computeGradientAndScore(LayerWorkspaceMgr workspaceMgr) {

    }

    @Override
    public INDArray params() {
        return null;
    }

    @Override
    public long numParams() {
        return 0;
    }

    @Override
    public long numParams(boolean backwards) {
        return 0;
    }

    @Override
    public void setParams(INDArray params) {

    }

    @Override
    public void setParamsViewArray(INDArray params) {
        throw new UnsupportedOperationException();
    }

    @Override
    public INDArray getGradientsViewArray() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setBackpropGradientsViewArray(INDArray gradients) {
        throw new UnsupportedOperationException();
    }


    public void fit(INDArray data) {
        this.x = data;
        fit();
    }

    @Override
    public void fit(INDArray data, LayerWorkspaceMgr workspaceMgr){
        fit(data);
    }

    /**
     * Change the dimensions with
     *
     * @deprecated Use {@link #fit(INDArray)}
     */
    @Deprecated
    public void fit(INDArray data, int nDims) {
        this.x = data;
        this.numDimensions = nDims;
        fit();
    }

    @Override
    public Gradient gradient() {
        /*MemoryWorkspace workspace =
                workspaceMode == WorkspaceMode.NONE ? new DummyWorkspace()
                        : Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread(
                        workspaceConfigurationExternal,
                        workspaceExternal);


        try (MemoryWorkspace ws = workspace.notifyScopeEntered())*/ {


            if (yIncs == null)
                yIncs = Y.like();
            if (gains == null)
                gains = Y.ulike().assign(1.0D);

            AtomicDouble sumQ = new AtomicDouble(0);
            /* Calculate gradient based on barnes hut approximation with positive and negative forces */
            INDArray posF = Y.like();
            INDArray negF = Y.like();

            tree = new SpTree(Y);

            tree.computeEdgeForces(rows, cols, vals, N, posF);
            for (int n = 0; n < N; n++) {
                INDArray temp = negF.slice(n);
                tree.computeNonEdgeForces(n, theta, temp, sumQ);
            }
            INDArray dC = posF.subi(negF.divi(sumQ));

            Gradient ret = new DefaultGradient();
            ret.gradientForVariable().put(Y_GRAD, dC);
            return ret;
        }
    }

    @Override
    public Pair<Gradient, Double> gradientAndScore() {
        return new Pair<>(gradient(), score());
    }

    @Override
    public int batchSize() {
        return 0;
    }

    @Override
    public NeuralNetConfiguration conf() {
        return null;
    }

    @Override
    public void setConf(NeuralNetConfiguration conf) {

    }

    /**
     * Return the matrix reduce to the NDim.
     */
    public INDArray getData() {
        return Y;
    }

    public void setData(INDArray data) {
        this.Y = data;
    }

    // TODO: find better solution for test
    public void setN(int N) {
        this.N = N;
    }

    public static class Builder {
        private int maxIter = 1000;
        private double realMin = 1e-12f;
        private double initialMomentum = 5e-1f;
        private double finalMomentum = 8e-1f;
        private double momentum = 5e-1f;
        private int switchMomentumIteration = 100;
        private boolean normalize = true;
        private int stopLyingIteration = 100;
        private double tolerance = 1e-5f;
        private double learningRate = 1e-1f;
        private boolean useAdaGrad = false;
        private double perplexity = 30;
        private double minGain = 1e-2f;
        private double theta = 0.5;
        private boolean invert = true;
        private int numDim = 2;
        private String similarityFunction = Distance.EUCLIDEAN.toString();
        private int vpTreeWorkers = 1;
        protected WorkspaceMode workspaceMode = WorkspaceMode.NONE;

        private INDArray staticInput;

        public Builder vpTreeWorkers(int vpTreeWorkers) {
            this.vpTreeWorkers = vpTreeWorkers;
            return this;
        }

        public Builder staticInit(INDArray staticInput) {
            this.staticInput = staticInput;
            return this;
        }

        public Builder minGain(double minGain) {
            this.minGain = minGain;
            return this;
        }

        public Builder perplexity(double perplexity) {
            this.perplexity = perplexity;
            return this;
        }

        public Builder useAdaGrad(boolean useAdaGrad) {
            this.useAdaGrad = useAdaGrad;
            return this;
        }

        public Builder learningRate(double learningRate) {
            this.learningRate = learningRate;
            return this;
        }


        public Builder tolerance(double tolerance) {
            this.tolerance = tolerance;
            return this;
        }

        public Builder stopLyingIteration(int stopLyingIteration) {
            this.stopLyingIteration = stopLyingIteration;
            return this;
        }

        public Builder normalize(boolean normalize) {
            this.normalize = normalize;
            return this;
        }

        public Builder setMaxIter(int maxIter) {
            this.maxIter = maxIter;
            return this;
        }

        public Builder setRealMin(double realMin) {
            this.realMin = realMin;
            return this;
        }

        public Builder setInitialMomentum(double initialMomentum) {
            this.initialMomentum = initialMomentum;
            return this;
        }

        public Builder setFinalMomentum(double finalMomentum) {
            this.finalMomentum = finalMomentum;
            return this;
        }

        public Builder setMomentum(double momentum) {
            this.momentum = momentum;
            return this;
        }

        public Builder setSwitchMomentumIteration(int switchMomentumIteration) {
            this.switchMomentumIteration = switchMomentumIteration;
            return this;
        }


        public Builder similarityFunction(String similarityFunction) {
            this.similarityFunction = similarityFunction;
            return this;
        }

        public Builder invertDistanceMetric(boolean invert) {
            this.invert = invert;
            return this;
        }

        public Builder theta(double theta) {
            this.theta = theta;
            return this;
        }

        public Builder numDimension(int numDim) {
            this.numDim = numDim;
            return this;
        }

        public Builder workspaceMode(WorkspaceMode workspaceMode){
            this.workspaceMode = workspaceMode;
            return this;
        }

        public BarnesHutTsne build() {
            return new BarnesHutTsne(numDim, similarityFunction, theta, invert, maxIter, realMin, initialMomentum,
                    finalMomentum, momentum, switchMomentumIteration, normalize, stopLyingIteration, tolerance,
                    learningRate, useAdaGrad, perplexity, null, minGain, vpTreeWorkers, workspaceMode, staticInput);
        }

    }


    @Override
    public void close(){
        //No-op
    }
}
