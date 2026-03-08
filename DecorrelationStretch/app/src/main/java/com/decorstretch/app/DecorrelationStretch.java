package com.decorstretch.app;

import android.graphics.Bitmap;

/**
 * Decorrelation Stretch implementation.
 *
 * Algorithm:
 * 1. Convert image to float RGB arrays
 * 2. Compute mean of each channel
 * 3. Compute covariance matrix of RGB channels
 * 4. Perform eigen-decomposition of the covariance matrix
 * 5. Transform (rotate) pixels into eigen-space
 * 6. Stretch each transformed channel to [0, 255]
 * 7. Rotate back to RGB space
 * 8. Clip and convert to output bitmap
 */
public class DecorrelationStretch {

    public interface ProgressListener {
        void onProgress(int percent, String message);
    }

    /**
     * Apply decorrelation stretch to a bitmap.
     *
     * @param input  Source bitmap (any config)
     * @param listener optional progress callback
     * @return      New ARGB_8888 bitmap with stretch applied
     */
    public static Bitmap apply(Bitmap input, ProgressListener listener) {
        int width = input.getWidth();
        int height = input.getHeight();
        int n = width * height;

        if (listener != null) listener.onProgress(5, "Reading pixels…");

        // --- 1. Extract pixel data into float channels ---
        int[] pixels = new int[n];
        input.getPixels(pixels, 0, width, 0, 0, width, height);

        float[] R = new float[n];
        float[] G = new float[n];
        float[] B = new float[n];

        for (int i = 0; i < n; i++) {
            int p = pixels[i];
            R[i] = (p >> 16) & 0xFF;
            G[i] = (p >> 8)  & 0xFF;
            B[i] = p         & 0xFF;
        }

        if (listener != null) listener.onProgress(15, "Computing statistics…");

        // --- 2. Compute channel means ---
        double meanR = mean(R), meanG = mean(G), meanB = mean(B);

        // --- 3. Covariance matrix (3×3, symmetric) ---
        double cRR = 0, cRG = 0, cRB = 0, cGG = 0, cGB = 0, cBB = 0;
        for (int i = 0; i < n; i++) {
            double r = R[i] - meanR;
            double g = G[i] - meanG;
            double b = B[i] - meanB;
            cRR += r * r;
            cRG += r * g;
            cRB += r * b;
            cGG += g * g;
            cGB += g * b;
            cBB += b * b;
        }
        cRR /= n; cRG /= n; cRB /= n;
        cGG /= n; cGB /= n; cBB /= n;

        double[][] cov = {
            {cRR, cRG, cRB},
            {cRG, cGG, cGB},
            {cRB, cGB, cBB}
        };

        if (listener != null) listener.onProgress(30, "Eigen-decomposition…");

        // --- 4. Eigen-decomposition (Jacobi for 3×3 symmetric) ---
        double[][] eigVec = new double[3][3]; // columns = eigenvectors
        double[] eigVal = new double[3];
        jacobiEigen(cov, eigVec, eigVal);

        if (listener != null) listener.onProgress(45, "Transforming channels…");

        // --- 5. Project pixels onto eigenvectors ---
        // projected[k][i] = eigVec[:,k] · (pixel[i] - mean)
        double[][] proj = new double[3][n];
        for (int i = 0; i < n; i++) {
            double r = R[i] - meanR;
            double g = G[i] - meanG;
            double b = B[i] - meanB;
            for (int k = 0; k < 3; k++) {
                proj[k][i] = eigVec[0][k] * r
                           + eigVec[1][k] * g
                           + eigVec[2][k] * b;
            }
        }

        if (listener != null) listener.onProgress(60, "Stretching…");

        // --- 6. Stretch each projected channel to [0, 255] ---
        for (int k = 0; k < 3; k++) {
            double min = proj[k][0], max = proj[k][0];
            for (int i = 1; i < n; i++) {
                if (proj[k][i] < min) min = proj[k][i];
                if (proj[k][i] > max) max = proj[k][i];
            }
            double range = max - min;
            if (range < 1e-10) range = 1.0; // avoid /0 for flat channels
            for (int i = 0; i < n; i++) {
                proj[k][i] = (proj[k][i] - min) / range * 255.0;
            }
        }

        if (listener != null) listener.onProgress(75, "Reconstructing image…");

        // --- 7. Rotate back to RGB ---
        // new_rgb = eigVec · proj  (eigVec rows = original channels)
        int[] outPixels = new int[n];
        for (int i = 0; i < n; i++) {
            double r = 0, g = 0, b = 0;
            for (int k = 0; k < 3; k++) {
                r += eigVec[0][k] * proj[k][i];
                g += eigVec[1][k] * proj[k][i];
                b += eigVec[2][k] * proj[k][i];
            }
            // Clip to [0, 255]
            int ri = clamp((int) Math.round(r));
            int gi = clamp((int) Math.round(g));
            int bi = clamp((int) Math.round(b));

            // Preserve original alpha
            int alpha = (pixels[i] >> 24) & 0xFF;
            outPixels[i] = (alpha << 24) | (ri << 16) | (gi << 8) | bi;
        }

        if (listener != null) listener.onProgress(90, "Building output bitmap…");

        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        out.setPixels(outPixels, 0, width, 0, 0, width, height);

        if (listener != null) listener.onProgress(100, "Done");
        return out;
    }

    // ---- Helpers ----

    private static double mean(float[] arr) {
        double s = 0;
        for (float v : arr) s += v;
        return s / arr.length;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /**
     * Jacobi iterative eigen-decomposition for a 3×3 symmetric matrix.
     * After return: eigVec columns are unit eigenvectors, eigVal holds eigenvalues.
     */
    private static void jacobiEigen(double[][] A, double[][] V, double[] d) {
        int n = 3;
        // Copy A into working matrix
        double[][] a = new double[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                a[i][j] = A[i][j];

        // V = identity
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                V[i][j] = (i == j) ? 1.0 : 0.0;

        int maxIter = 100;
        for (int iter = 0; iter < maxIter; iter++) {
            // Find largest off-diagonal element
            int p = 0, q = 1;
            double maxVal = Math.abs(a[0][1]);
            for (int i = 0; i < n - 1; i++) {
                for (int j = i + 1; j < n; j++) {
                    if (Math.abs(a[i][j]) > maxVal) {
                        maxVal = Math.abs(a[i][j]);
                        p = i; q = j;
                    }
                }
            }
            if (maxVal < 1e-12) break; // converged

            // Compute rotation angle
            double theta = (a[q][q] - a[p][p]) / (2.0 * a[p][q]);
            double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
            double c = 1.0 / Math.sqrt(t * t + 1);
            double s = t * c;

            // Apply Jacobi rotation
            double[][] G = new double[n][n];
            for (int i = 0; i < n; i++) G[i][i] = 1.0;
            G[p][p] = c; G[q][q] = c;
            G[p][q] = s; G[q][p] = -s;

            a = matMul(transpose(G), matMul(a, G));
            V = matMul(V, G);
        }

        for (int i = 0; i < n; i++) d[i] = a[i][i];
    }

    private static double[][] matMul(double[][] A, double[][] B) {
        int n = A.length;
        double[][] C = new double[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                for (int k = 0; k < n; k++)
                    C[i][j] += A[i][k] * B[k][j];
        return C;
    }

    private static double[][] transpose(double[][] A) {
        int n = A.length;
        double[][] T = new double[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                T[i][j] = A[j][i];
        return T;
    }
}
