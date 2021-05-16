/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui;

import android.util.Pools;
import android.view.SurfaceControl;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Provides a synchronized pool of {@link SurfaceControl.Transaction}s to minimize allocations.
 */
@Singleton
public class TransactionPool {
    private final Pools.SynchronizedPool<SurfaceControl.Transaction> mTransactionPool =
            new Pools.SynchronizedPool<>(4);

    @Inject
    TransactionPool() {
    }

    /** Gets a transaction from the pool. */
    public SurfaceControl.Transaction acquire() {
        SurfaceControl.Transaction t = mTransactionPool.acquire();
        if (t == null) {
            return new SurfaceControl.Transaction();
        }
        return t;
    }

    /**
     * Return a transaction to the pool. DO NOT call {@link SurfaceControl.Transaction#close()} if
     * returning to pool.
     */
    public void release(SurfaceControl.Transaction t) {
        if (!mTransactionPool.release(t)) {
            t.close();
        }
    }
}
