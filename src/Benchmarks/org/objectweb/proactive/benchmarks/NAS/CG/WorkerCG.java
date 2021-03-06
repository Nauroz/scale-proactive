/*
 * ################################################################
 *
 * ProActive Parallel Suite(TM): The Java(TM) library for
 *    Parallel, Distributed, Multi-Core Computing for
 *    Enterprise Grids & Clouds
 *
 * Copyright (C) 1997-2012 INRIA/University of
 *                 Nice-Sophia Antipolis/ActiveEon
 * Contact: proactive@ow2.org or contact@activeeon.com
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; version 3 of
 * the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307
 * USA
 *
 * If needed, contact us to obtain a release under GPL Version 2 or 3
 * or a different license than the AGPL.
 *
 *  Initial developer(s):               The ProActive Team
 *                        http://proactive.inria.fr/team_members.htm
 *  Contributor(s):
 *
 * ################################################################
 * $$PROACTIVE_INITIAL_DEV$$
 */
package org.objectweb.proactive.benchmarks.NAS.CG;

import java.text.DecimalFormat;
import java.util.Arrays;

import org.objectweb.proactive.api.PAActiveObject;
import org.objectweb.proactive.api.PASPMD;
import org.objectweb.proactive.benchmarks.NAS.util.Random;
import org.objectweb.proactive.extensions.timitspmd.util.Timed;
import org.objectweb.proactive.extensions.timitspmd.util.TimerCounter;
import org.objectweb.proactive.extensions.timitspmd.util.observing.Event;
import org.objectweb.proactive.extensions.timitspmd.util.observing.EventObserver;
import org.objectweb.proactive.extensions.timitspmd.util.observing.defaultobserver.DefaultEventData;
import org.objectweb.proactive.extensions.timitspmd.util.observing.defaultobserver.DefaultEventObserver;


/**
 * NAS PARALLEL BENCHMARKS
 * 
 * Kernel CG
 * 
 * A conjugate gradient method is used to compute an approximation to the smallest eigenvalue of a
 * large, sparse, symmetric positive definite matrix. This kernel is typical of unstructured grid
 * computations in that it tests irregular long distance communication, employing unstructured
 * matrix vector multiplication.
 */

public class WorkerCG extends Timed {

    /** TimIt related variables * */
    public static final boolean COMMUNICATION_PATTERN_OBSERVING_MODE = false;
    private TimerCounter T_total = new TimerCounter("Total");
    private TimerCounter T_exchangeWaitTime = new TimerCounter("ExchangeWaitTime");
    public DefaultEventObserver E_mflops;

    /** Group Information */
    private int rank;
    private int groupSize;
    private CGProblemClass clss;
    private WorkerCG me;

    private double tran;
    private double amult = 1220703125.0d;
    private Random rng;
    private int npcols;
    private int nprows;
    private int naa;
    private int firstcol;
    private int lastcol;
    private int firstrow;
    private int lastrow;
    private double zeta;
    private int nzz;
    private double[] a;
    private int[] colidx;
    private int[] rowstr;
    private double[] x;
    private int l2npcols;
    private int[] reduce_exch_proc;
    private int[] reduce_send_starts;
    private int[] reduce_send_lengths;
    private int[] reduce_recv_starts;
    private int[] reduce_recv_lengths;
    private int send_start;
    private int send_len;
    private int exch_proc;
    private int exch_recv_length;
    private double rnorm;
    private double[] z;
    private double[] p;
    private double[] q;
    private double[] r;
    private double[] w;
    final int cgitmax = 25;
    double[] norm_temp1;
    double[] norm_temp2;
    /** The array of stubs to all workers of the group */
    private int currentIter_ = 0;

    /** Pre-calculated number of rows and columns */
    private int numberOfRows;
    private int numberOfColumns;

    public WorkerCG() {
    }

    /**
     * Constructor
     */
    public WorkerCG(CGProblemClass clss, Integer npcols, Integer nprows, Integer nzz) {
        this.clss = clss;

        this.npcols = npcols;
        this.nprows = nprows;
        this.naa = clss.na;
        this.nzz = nzz;
    }

    public void start() {
        this.init();

        /* Printout initial NPB info */
        if (rank == 0) {
            KernelCG.printStarted(clss.KERNEL_NAME, clss.PROBLEM_CLASS_NAME, new long[] { clss.na },
                    clss.niter, groupSize, clss.nonzer, clss.shift);
        }

        // add 1 to all size to keep Fortran index
        a = new double[nzz + 1];
        colidx = new int[nzz + 1];
        rowstr = new int[naa + 1 + 1];
        x = new double[(naa / nprows) + 2 + 1];
        reduce_exch_proc = new int[npcols + 1];
        reduce_send_starts = new int[npcols + 1];
        reduce_send_lengths = new int[npcols + 1];
        reduce_recv_starts = new int[npcols + 1];
        reduce_recv_lengths = new int[npcols + 1];
        z = new double[(naa / nprows) + 2 + 1];
        p = new double[(naa / nprows) + 2 + 1];
        q = new double[(naa / nprows) + 2 + 1];
        r = new double[(naa / nprows) + 2 + 1];
        w = new double[(naa / nprows) + 2 + 1];
        norm_temp1 = new double[2 + 1];
        norm_temp2 = new double[2 + 1];

        // Set up partition's submatrix info: firstcol, lastcol, firstrow, lastrow
        setup_submatrix_info();

        // Inialize random number generator
        rng.setSeed(tran);
        rng.setGmult(amult);
        zeta = rng.randlc(); // tran, amult);
        tran = rng.getSeed();

        // Set up partition's sparse random matrix for given class size
        makea(naa, nzz, a, colidx, rowstr, clss.nonzer, clss.rcond, clss.shift);

        // Note: as a result of the above call to makea:
        // values of j used in indexing rowstr go from 1 --> lastrow-firstrow+1
        // values of colidx which are col indexes go from firstcol --> lastcol
        // So:
        // Shift the col index vals from actual (firstcol --> lastcol )
        // to local, i.e., (1 --> lastcol-firstcol+1)
        int k;

        // Note: as a result of the above call to makea:
        // values of j used in indexing rowstr go from 1 --> lastrow-firstrow+1
        // values of colidx which are col indexes go from firstcol --> lastcol
        // So:
        // Shift the col index vals from actual (firstcol --> lastcol )
        // to local, i.e., (1 --> lastcol-firstcol+1)
        int temp;

        for (int i = 1; i <= this.numberOfRows; i++) {
            temp = (rowstr[i + 1] - 1);
            for (k = rowstr[i]; k <= temp; k++) {
                colidx[k] -= this.firstcol;
                colidx[k]++;
            }
        }

        // set starting vector to (1, 1, .... 1)
        java.util.Arrays.fill(this.x, 1, ((clss.na / nprows) + 1) + 1, 1.0d);

        this.zeta = 0.0d;

        // The call to conjugate gradient routine
        this.conjGrad();

        // zeta = shift + 1/(x.z)
        // So, first: (x.z)
        // Also, find norm of z
        // So, first: (z.z)
        this.norm_temp1[1] = 0.0d;
        this.norm_temp1[2] = 0.0d;

        for (int i = 1; i <= this.numberOfColumns; i++) {
            this.norm_temp1[1] += (x[i] * z[i]);
            this.norm_temp1[2] += (z[i] * z[i]);
        }

        for (int i = 1; i <= this.l2npcols; i++) {
            double[] destArray = new double[2];
            PASPMD.exchange("reduce" + i, reduce_exch_proc[i], new double[] { this.norm_temp1[1],
                    this.norm_temp1[2] }, 0, destArray, 0, 2);
            this.norm_temp1[1] += destArray[0];
            this.norm_temp1[2] += destArray[1];
        }

        this.norm_temp1[2] = 1.0d / Math.sqrt(this.norm_temp1[2]);

        // Normalize z to obtain x
        for (int i = 1; i <= this.numberOfColumns; i++) {
            x[i] = norm_temp1[2] * z[i];
        }

        // End of one untimed iteration
        // set starting vector to (1, 1, .... 1)
        Arrays.fill(x, 1, (this.clss.na / nprows) + 2, 1.0d);
        this.zeta = 0.0d;

        T_total.start();
        T_exchangeWaitTime.reset();

        me.iterate(1);
    }

    public void iterate(int iteration) {
        if (iteration <= this.clss.niter) {
            // --------------------------------------------------------------------
            // ---->
            // Main iteration for inverse power method
            // ---->
            // --------------------------------------------------------------------

            // The call to conjugate gradient routine
            this.conjGrad();

            // zeta = shift + 1/(x.z)
            // So, first: (x.z)
            // Also, find norm of z
            // So, first: (z.z)
            this.norm_temp1[1] = 0.0d;
            this.norm_temp1[2] = 0.0d;

            for (int i = 1; i <= this.numberOfColumns; i++) {
                this.norm_temp1[1] += (x[i] * z[i]);
                this.norm_temp1[2] += (z[i] * z[i]);
            }

            for (int i = 1; i <= this.l2npcols; i++) {
                double[] destArray = new double[2];
                PASPMD.exchange("reduce" + i, reduce_exch_proc[i], new double[] { this.norm_temp1[1],
                        this.norm_temp1[2] }, 0, destArray, 0, 2);
                this.norm_temp1[1] += destArray[0];
                this.norm_temp1[2] += destArray[1];
            }

            this.norm_temp1[2] = 1.0d / Math.sqrt(this.norm_temp1[2]);

            if (rank == 0) {
                this.zeta = this.clss.shift + (1.0d / this.norm_temp1[1]);

                if (iteration == 1) {
                    System.out.println("   iteration           ||r||                 zeta");
                }
                DecimalFormat norm = new DecimalFormat("0.00000000000000E00");
                DecimalFormat norm2 = new DecimalFormat("00.00000000000000E00");
                System.out.println("   " + iteration + " \t\t" + norm.format(this.rnorm) + " \t" +
                    norm2.format(this.zeta));
            }

            // Normalize z to obtain x
            for (int j = 1; j <= this.numberOfColumns; j++) {
                x[j] = norm_temp1[2] * z[j];
            }

            me.iterate(iteration + 1);

        } else {
            // --------------------------------------------------------------------
            // End of main iter inv pow method
            // End of timed section
            // --------------------------------------------------------------------
            T_total.stop();

            super.getEventObservable().notifyObservers(new Event(E_mflops, getMflops()));

            if (rank == 0) {
                super.finalizeTimed(this.rank, verify() ? "" : "UNSUCCESSFUL");
            } else {
                super.finalizeTimed(this.rank, "");
            }
        }
    }

    /** The method to kill this Active object */
    public void terminate() {
        try {
            PAActiveObject.getBodyOnThis().terminate();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void init() {
        this.groupSize = PASPMD.getMySPMDGroupSize();
        this.rank = PASPMD.getMyRank();
        this.me = (WorkerCG) PAActiveObject.getStubOnThis();

        // Setup the timing system
        E_mflops = new DefaultEventObserver("mflops", DefaultEventData.MIN, DefaultEventData.MIN);
        super.activate(new TimerCounter[] { T_total, T_exchangeWaitTime }, new EventObserver[] { E_mflops });

        rng = new Random();
        tran = 314159265.0d;
    }

    private void setup_submatrix_info() {
        int proc_row;
        int proc_col;
        int col_size;
        int row_size;
        int j;
        int div_factor;

        proc_row = rank / npcols;
        proc_col = rank - (proc_row * npcols);

        // If naa evenly divisible by npcols, then it is evenly divisible by nprows
        if ((naa / npcols * npcols) == naa) { // .eq.
            col_size = naa / npcols;
            firstcol = (proc_col * col_size) + 1;
            lastcol = firstcol - 1 + col_size;
            row_size = naa / nprows;
            firstrow = (proc_row * row_size) + 1;
            lastrow = firstrow - 1 + row_size;
            // If naa not evenly divisible by npcols, then first subdivide for nprows and then, if
            // npcols not equal to nprows (local_i.e., not a sq number of procs),
            // get col subdivisions by dividing by 2 each row subdivision.
        } else {
            if (proc_row < (naa - (naa / nprows * nprows))) { // .lt.
                row_size = (naa / nprows) + 1;
                firstrow = (proc_row * row_size) + 1;
                lastrow = firstrow - 1 + row_size;
            } else {
                row_size = naa / nprows;
                firstrow = ((naa - (naa / nprows * nprows)) * (row_size + 1)) +
                    ((proc_row - (naa - (naa / nprows * nprows))) * row_size) + 1;
                lastrow = firstrow - 1 + row_size;
            }
            if (npcols == nprows) {
                if (proc_col < (naa - (naa / npcols * npcols))) {
                    col_size = (naa / npcols) + 1;
                    firstcol = (proc_col * col_size) + 1;
                    lastcol = firstcol - 1 + col_size;
                } else {
                    col_size = naa / npcols;
                    firstcol = ((naa - (naa / npcols * npcols)) * (col_size + 1)) +
                        ((proc_col - (naa - (naa / npcols * npcols))) * col_size) + 1;
                    lastcol = firstcol - 1 + col_size;
                }
            } else {
                if ((proc_col / 2) < (naa - (naa / (npcols / 2) * (npcols / 2)))) {
                    col_size = (naa / (npcols / 2)) + 1;
                    firstcol = ((proc_col / 2) * col_size) + 1;
                    lastcol = firstcol - 1 + col_size;
                } else {
                    col_size = naa / (npcols / 2);
                    firstcol = ((naa - (naa / (npcols / 2) * (npcols / 2))) * (col_size + 1)) +
                        (((proc_col / 2) - (naa - (naa / (npcols / 2) * (npcols / 2)))) * col_size) + 1;
                    lastcol = firstcol - 1 + col_size;
                }

                if ((rank % 2) == 0) {
                    lastcol = firstcol - 1 + ((col_size - 1) / 2) + 1;
                } else {
                    firstcol = firstcol + ((col_size - 1) / 2) + 1;
                    lastcol = firstcol - 1 + (col_size / 2);
                }
            }
        }
        this.numberOfRows = this.lastrow - this.firstrow + 1; // added
        this.numberOfColumns = this.lastcol - this.firstcol + 1; // added

        if (npcols == nprows) {
            send_start = 1;
            send_len = this.numberOfRows; // lastrow - firstrow + 1;
        } else {
            if ((rank & 1) == 0) { // if ((rank % 2) == 0) {
                send_start = 1;
                send_len = ((1 + lastrow) - firstrow + 1) / 2;
            } else {
                send_start = (((1 + lastrow) - firstrow + 1) / 2) + 1;
                send_len = this.numberOfRows / 2; // (lastrow - firstrow + 1)
                // / 2;
            }
        }

        // Transpose exchange processor
        if (npcols == nprows) {
            exch_proc = ((rank % nprows) * nprows) + (rank / nprows);
        } else {
            exch_proc = (2 * ((((rank / 2) % nprows) * nprows) + (rank / 2 / nprows))) + (rank % 2);
        }

        int l2 = npcols / 2;
        l2npcols = 0;
        while (l2 > 0) {
            l2npcols++;
            l2 /= 2;
        }

        // Set up the reduce phase schedules...
        div_factor = npcols;
        for (int i = 1; i <= l2npcols; i++) {
            j = ((proc_col + (div_factor / 2)) % div_factor) + (proc_col / div_factor * div_factor);
            reduce_exch_proc[i] = (proc_row * npcols) + j;

            div_factor = div_factor / 2;
        }

        for (int i = l2npcols; i >= 1; i--) {
            if (nprows == npcols) {
                reduce_send_starts[i] = send_start;
                reduce_send_lengths[i] = send_len;
                reduce_recv_lengths[i] = lastrow - firstrow + 1;
            } else {
                reduce_recv_lengths[i] = send_len;
                if (i == l2npcols) {
                    reduce_send_lengths[i] = (lastrow - firstrow + 1) - send_len;
                    if ((rank & 1) == 0) { // if ((rank / 2 * 2) == rank) {
                        reduce_send_starts[i] = send_start + send_len;
                    } else {
                        reduce_send_starts[i] = 1;
                    }
                } else {
                    reduce_send_lengths[i] = send_len;
                    reduce_send_starts[i] = send_start;
                }
            }
            reduce_recv_starts[i] = send_start;
        }
        this.exch_recv_length = this.numberOfColumns + 1;
    }

    private void makea(int n, int nz, double[] a, int[] colidx, int[] rowstr, int nonzer, double rcond,
            double shift) {
        // ---------------------------------------------------------------------
        // generate the test problem for benchmark 6
        // makea generates a sparse matrix with a
        // prescribed sparsity distribution
        //
        // parameter type usage
        //
        // input
        //
        // n i number of cols/rows of matrix
        // nz i nonzeros as declared array size
        // rcond r*8 condition number
        // shift r*8 main diagonal shift
        //
        // output
        //
        // a r*8 array for nonzeros
        // colidx i col indices
        // rowstr i row pointers
        //
        // workspace
        //
        // iv, arow, acol i
        // v, aelt r*8
        // ---------------------------------------------------------------------
        int i;
        int nnza;
        int ivelt;
        int ivelt1;
        int irow;
        int nzv;
        int jcol;
        int iouter;

        int[] iv = new int[(2 * naa) + 1 + 1];
        double[] v = new double[naa + 1 + 1];
        int[] acol = new int[nzz + 1];
        int[] arow = new int[nzz + 1];
        double[] aelt = new double[nzz + 1];

        // nonzer is approximately (int(sqrt(nnza /n)));
        double size;
        double ratio;
        double scale;

        size = 1.0d;
        ratio = Math.pow(rcond, (1.0 / (float) n));
        nnza = 0;

        // Initialize colidx(n+1 .. 2n) to zero.
        // Used by sprnvc to mark nonzero positions
        Arrays.fill(colidx, n + 1, (2 * n) + 1, 0);

        for (iouter = 1; iouter <= n; iouter++) {
            nzv = nonzer;
            sprnvc(n, nzv, v, iv, colidx, 0, colidx, n);
            nzv = vecset(n, v, iv, nzv, iouter, .5);

            for (ivelt = 1; ivelt <= nzv; ivelt++) {
                jcol = iv[ivelt];
                if ((jcol >= firstcol) && (jcol <= lastcol)) {
                    scale = size * v[ivelt];
                    for (ivelt1 = 1; ivelt1 <= nzv; ivelt1++) {
                        irow = iv[ivelt1];
                        if ((irow >= firstrow) && (irow <= lastrow)) {
                            nnza++;
                            if (nnza > nz) {
                                System.out.println("Space for matrix elements exceeded in makea");
                                System.out.println("nnza, nzmax = " + nnza + ", " + nz);
                                System.out.println(" iouter = " + iouter);
                                this.terminate(); // return
                            }
                            acol[nnza] = jcol;
                            arow[nnza] = irow;
                            aelt[nnza] = v[ivelt1] * scale;
                        }
                    }
                }
            }
            size = size * ratio;
        }

        // ... add the identity * rcond to the generated matrix to bound
        // the smallest eigenvalue from below by rcond
        for (i = firstrow; i <= lastrow; i++) {
            if ((i >= firstcol) && (i <= lastcol)) {
                iouter = n + i;
                nnza++;
                if (nnza > nz) {
                    System.out.println("Space for matrix elements exceeded in makea");
                    System.out.println("nnza, nzmax = " + nnza + ", " + nz);
                    System.out.println(" iouter = " + iouter);
                    this.terminate();
                    return;
                }
                acol[nnza] = i;
                arow[nnza] = i;
                aelt[nnza] = rcond - shift;
            }
        }

        // ... make the sparse matrix from list of elements with duplicates
        // (v and iv are used as workspace)
        sparse(a, colidx, rowstr, n, arow, acol, aelt, v, iv, 0, iv, n, nnza);
    }

    private void sparse(double[] a, int[] colidx, int[] rowstr, int n, int[] arow, int[] acol, double[] aelt,
            double[] x, int[] mark, int mark_offst, int[] nzloc, int nzloc_offst, int nnza) {

        // generate a sparse matrix from a list of
        // [col, row, element] tri
        int ind;
        int j;
        int jajp1;
        int nza;
        int k;
        int nzrow;
        double xi;
        int temp;

        // ...count the number of triples in each row
        for (j = 1; j <= n; j++) {
            rowstr[j] = 0;
            mark[j + mark_offst] = 0;
        }
        rowstr[n + 1] = 0;

        for (nza = 1; nza <= nnza; nza++) {
            j = (arow[nza] - firstrow + 1) + 1;
            rowstr[j]++;
        }

        rowstr[1] = 1;
        temp = this.numberOfRows + 1;
        for (j = 2; j <= temp; j++) {
            rowstr[j] = rowstr[j] + rowstr[j - 1];
        }

        // ... rowstr(j) now is the location of the first nonzero of row j of a
        // ... do a bucket sort of the triples on the row index
        for (nza = 1; nza <= nnza; nza++) {
            j = arow[nza] - firstrow + 1;
            k = rowstr[j];
            a[k] = aelt[nza];
            colidx[k] = acol[nza];
            rowstr[j] = rowstr[j] + 1;
        }

        // ... rowstr(j) now points to the first element of row j+1
        for (j = this.numberOfRows; j >= 1; j--) {
            // j--) {
            rowstr[j + 1] = rowstr[j];
        }
        rowstr[1] = 1;

        // ... generate the actual output rows by adding elements
        nza = 0;
        for (ind = 1; ind <= n; ind++) {
            x[ind] = 0.0d;
            mark[ind + mark_offst] = 0;
        }

        jajp1 = rowstr[1];
        for (j = 1; j <= this.numberOfRows; j++) {
            nzrow = 0;

            // ...loop over the jth row of a
            for (k = jajp1; k <= (rowstr[j + 1] - 1); k++) {
                ind = colidx[k];
                x[ind] = x[ind] + a[k];
                if ((mark[ind + mark_offst] == 0) && (x[ind] != 0)) {
                    mark[ind + mark_offst] = 1;
                    nzrow = nzrow + 1;
                    nzloc[nzrow + nzloc_offst] = ind;
                }
            }

            // ... extract the nonzeros of this row
            for (k = 1; k <= nzrow; k++) {
                ind = nzloc[k + nzloc_offst];
                mark[ind + mark_offst] = 0;
                xi = x[ind];
                x[ind] = 0.;
                if (xi != 0.) {
                    nza = nza + 1;
                    a[nza] = xi;
                    colidx[nza] = ind;
                }
            }

            jajp1 = rowstr[j + 1];
            rowstr[j + 1] = nza + rowstr[1];
        }
    }

    private int vecset(int n, double[] v, int[] iv, int nzv, int i, double val) {
        boolean set = false;
        for (int k = 1; k <= nzv; k++) {
            if (iv[k] == i) {
                v[k] = val;
                set = true;
            }
        }
        if (!set) {
            nzv = nzv + 1;
            v[nzv] = val;
            iv[nzv] = i;
        }
        return nzv;
    }

    // generate a sparse n-vector (v, iv) having nzv nonzeros
    //
    // mark(i) is set to 1 if position i is nonzero.
    // mark is all zero on entry and is reset to all zero before exit
    // this corrects a performance bug found by John G. Lewis, caused by
    // reinitialization of mark on every one of the n calls to sprnvc
    private void sprnvc(int n, int nz, double[] v, int[] iv, int[] nzloc, int nzloc_offst, int[] mark,
            int mark_offst) {
        int nzrow = 0;
        int nzv = 0;
        int i;
        int ii;
        int nn1 = 1;
        double vecelt;
        double vecloc;

        while (true) {
            nn1 <<= 1; // nn1 = 2 * nn1;
            if (nn1 >= n) {
                break;
            }
        }

        // nn1 is the smallest power of two not less than n
        while (true) {
            if (nzv >= nz) {
                for (ii = 1; ii <= nzrow; ii++) {
                    i = nzloc[ii + nzloc_offst];
                    mark[i + mark_offst] = 0;
                }
                return;
            }

            rng.setSeed(tran);
            rng.setGmult(amult);
            vecelt = rng.randlc();

            // generate an integer between 1 and n in a portable manner
            vecloc = rng.randlc(); // tran, amult);
            tran = rng.getSeed();
            i = (int) (nn1 * vecloc) + 1;
            if (i > n) {
                continue;
            }

            if (mark[i + mark_offst] == 0) {
                mark[i + mark_offst] = 1;
                nzrow = nzrow + 1;
                nzloc[nzrow + nzloc_offst] = i;
                nzv = nzv + 1;
                v[nzv] = vecelt;
                iv[nzv] = i;
            }
        }
    }

    /**
     * The conjugate gradient parallel method
     */
    private void conjGrad() {
        double sum;
        double rho;
        double rho0;
        double d;
        double alpha;
        double beta;
        int i;
        int j;
        int k;

        // Initialize the CG algorithm:
        int temp;
        temp = ((naa / nprows) + 1);
        for (i = 1; i <= temp; i++) {
            this.q[i] = 0.0d;
            this.z[i] = 0.0d;
            this.r[i] = this.x[i];
            this.p[i] = this.r[i];
            this.w[i] = 0.0d;
        }

        // rho = r.r
        // Now, obtain the norm of r: First, sum squares of r elements locally...
        sum = 0.0d;
        for (i = 1; i <= this.numberOfColumns; i++) {
            sum += (r[i] * r[i]);
        }

        // Exchange and sum with procs identified in reduce_exch_proc
        for (i = 1; i <= this.l2npcols; i++) {
            double[] doubleArray = new double[1];
            PASPMD.exchange("reduce" + i, this.reduce_exch_proc[i], new double[] { sum }, 0, doubleArray, 0,
                    1);
            sum += doubleArray[0];
        }
        rho = sum;

        // q = A.p
        // The partition submatrix-vector multiply: use workspace w
        for (int cgit = 1; cgit <= cgitmax; cgit++) {
            for (j = 1; j <= this.numberOfRows; j++) {
                sum = 0.0d;
                temp = (rowstr[j + 1] - 1);
                for (k = rowstr[j]; k <= temp; k++) {
                    sum += (a[k] * p[colidx[k]]);
                }
                w[j] = sum;
            }

            // Sum the partition submatrix-vec A.p's across rows Exchange and sum piece of w with
            // procs identified in reduce_exch_proc
            for (i = this.l2npcols; i >= 1; i--) {
                PASPMD.exchange("reduce" + i, reduce_exch_proc[i], w, this.reduce_send_starts[i], q,
                        this.reduce_recv_starts[i], this.reduce_recv_lengths[i]);

                temp = ((this.send_start + this.reduce_recv_lengths[i]) - 1);
                for (j = this.send_start; j <= temp; j++) {
                    this.w[j] += this.q[j];
                }
            }

            // Exchange piece of q with transpose processor
            if (this.l2npcols != 0) {
                if (this.rank == this.exch_proc) {
                    System.arraycopy(this.w, this.send_start, this.q, 1, this.exch_recv_length);
                    this.currentIter_++;
                } else {
                    PASPMD.exchange("reduce" + i, this.exch_proc, this.w, this.send_start, q, 1,
                            exch_recv_length);
                }
            } else {
                System.arraycopy(this.w, 0, this.q, 0, this.exch_recv_length);
            }

            // Clear w for reuse...
            Arrays.fill(w, 1, Math.max(this.numberOfRows, this.numberOfColumns) + 1, 0.0d);

            // Obtain p.q
            sum = 0.0d;
            for (i = 1; i <= this.numberOfColumns; i++) {
                sum += (this.p[i] * this.q[i]);
            }

            // Obtain d with a sum-reduce
            for (i = 1; i <= this.l2npcols; i++) {
                double[] doubleArray = new double[1];
                PASPMD.exchange("reduce" + i, this.reduce_exch_proc[i], new double[] { sum }, 0, doubleArray,
                        0, 1);
                sum += doubleArray[0];
            }
            d = sum;

            // Obtain alpha = rho / (p.q)
            alpha = rho / d;

            // Save a temporary of rho
            rho0 = rho;

            // Obtain z = z + alpha*p
            // and r = r - alpha*q
            for (i = 1; i <= this.numberOfColumns; i++) {
                this.z[i] += (alpha * this.p[i]);
                this.r[i] -= (alpha * this.q[i]);
            }

            // rho = r.r
            // Now, obtain the norm of r: First, sum squares of r elements locally...
            sum = 0.0d;
            for (i = 1; i <= this.numberOfColumns; i++) {
                sum += (this.r[i] * this.r[i]);
            }

            // Obtain rho with a sum reduce
            for (i = 1; i <= this.l2npcols; i++) {
                double[] doubleArray = new double[1];
                PASPMD.exchange("reduce" + i, this.reduce_exch_proc[i], new double[] { sum }, 0, doubleArray,
                        0, 1);
                sum += doubleArray[0];
            }
            rho = sum;

            // Obtain beta:
            beta = rho / rho0;

            // p = r + beta*p
            for (i = 1; i <= this.numberOfColumns; i++) {
                this.p[i] = this.r[i] + (beta * this.p[i]);
            }
        }

        // Compute residual norm explicitly: ||r|| = ||x - A.z||
        // First, form A.z
        // The partition submatrix-vector multiply
        for (i = 1; i <= this.numberOfRows; i++) {
            sum = 0.0d;
            temp = (this.rowstr[i + 1] - 1);
            for (j = this.rowstr[i]; j <= temp; j++) {
                sum += (this.a[j] * this.z[this.colidx[j]]);
            }
            this.w[i] = sum;
        }

        // Sum the partition submatrix-vec A.z's across rows
        for (i = this.l2npcols; i >= 1; i--) {
            PASPMD.exchange("reduce" + i, reduce_exch_proc[i], w, this.reduce_send_starts[i], r,
                    this.reduce_recv_starts[i], this.reduce_recv_lengths[i]);
            temp = ((this.send_start + this.reduce_recv_lengths[i]) - 1);
            for (j = this.send_start; j <= temp; j++) {
                this.w[j] += this.r[j];
            }
        }

        // Exchange piece of q with transpose processor
        if (this.l2npcols != 0) {
            if (this.rank == this.exch_proc) {
                System.arraycopy(this.w, this.send_start, this.r, 1, this.exch_recv_length);
                this.currentIter_++;
            } else {
                PASPMD
                        .exchange("reduce" + i, this.exch_proc, this.w, this.send_start, r, 1,
                                exch_recv_length);
            }
        } else {
            System.arraycopy(this.w, 0, this.r, 0, this.w.length);
        }

        // At this point, r contains A.z
        sum = 0.0d;
        for (i = 1; i <= this.numberOfColumns; i++) {
            d = x[i] - r[i];
            sum += (d * d);
        }

        // Obtain d with a sum-reduce
        for (i = 1; i <= this.l2npcols; i++) {
            double[] doubleArray = new double[1];
            PASPMD.exchange("reduce" + i, this.reduce_exch_proc[i], new double[] { sum }, 0, doubleArray, 0,
                    1);
            sum += doubleArray[0];
        }
        d = sum;

        if (this.rank == 0) {
            this.rnorm = Math.sqrt(sum);
        }
    } // -> START ITER HERE !

    private boolean verify() {
        boolean verified;
        double epsilon = 0.0000000001d;

        if (clss.PROBLEM_CLASS_NAME != 'U') {
            if (Math.abs(zeta - clss.zeta_verify_value) <= epsilon) {
                verified = true;
                System.out.println(" VERIFICATION SUCCESSFUL");
                System.out.println(" Zeta is    " + zeta);
                System.out.println(" Error is   " + (zeta - clss.zeta_verify_value));
            } else {
                verified = false;
                System.out.println(" VERIFICATION FAILED");
                System.out.println(" Zeta                " + zeta);
                System.out.println(" The correct zeta is " + clss.zeta_verify_value);
            }
        } else {
            verified = false;
            System.out.println(" Problem size unknown");
            System.out.println(" NO VERIFICATION PERFORMED");
        }
        return verified;
    }

    private double getMflops() {
        double time = T_total.getTotalTime() / 1000.0;
        int nzp1 = clss.nonzer * (clss.nonzer + 1);
        double mflops = ((2 * clss.niter * clss.na) * (3.0 + nzp1 + (25.0 * (5.0 + nzp1)) + 3.0)) / time /
            1000000.0;

        return mflops;
    }
}