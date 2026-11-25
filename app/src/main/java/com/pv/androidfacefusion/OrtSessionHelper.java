package com.pv.androidfacefusion;

import android.util.Log;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Helper utility to create optimized ONNX Runtime sessions configured with
 * NNAPI hardware acceleration (with safe CPU fallback) and thermal optimizations.
 */
public class OrtSessionHelper {
    private static final String TAG = "OrtSessionHelper";

    public static OrtSession createSession(OrtEnvironment env, String modelPath, String tag) throws OrtException {
        int optimalThreads = getOptimalThreadCount();

        // 1. Try Hardware Acceleration (NNAPI) first to speed up inference and reduce CPU heat
        try (OrtSession.SessionOptions nnapiOptions = new OrtSession.SessionOptions()) {
            nnapiOptions.setIntraOpNumThreads(optimalThreads);
            nnapiOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            nnapiOptions.addConfigEntry("session.intra_op.allow_spinning", "0");
            nnapiOptions.addNnapi();

            OrtSession session = env.createSession(modelPath, nnapiOptions);
            Log.i(tag, "Successfully initialized model with NNAPI + " + optimalThreads + " threads: " + modelPath);
            return session;
        } catch (Exception e) {
            Log.w(tag, "NNAPI unavailable for " + modelPath + ", falling back to optimized CPU: " + e.getMessage());
        }

        // 2. Fallback to Multi-threaded CPU with thermal-friendly config
        try (OrtSession.SessionOptions cpuOptions = new OrtSession.SessionOptions()) {
            cpuOptions.setIntraOpNumThreads(optimalThreads);
            cpuOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            cpuOptions.addConfigEntry("session.intra_op.allow_spinning", "0");

            OrtSession session = env.createSession(modelPath, cpuOptions);
            Log.i(tag, "Successfully initialized model on CPU with " + optimalThreads + " threads (ALL_OPT): " + modelPath);
            return session;
        }
    }

    public static int getOptimalThreadCount() {
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(4, cores));
    }
}
