package com.auraboot.framework.event.context;

import java.util.List;
import org.springframework.stereotype.Component;

/** Binds domain contexts and restores the worker state, including exceptional exits. */
@Component
public class ThreadContextScopes {
    private final List<ThreadContextContributor> contributors;

    public ThreadContextScopes(List<ThreadContextContributor> contributors) {
        this.contributors = List.copyOf(contributors);
    }

    public List<ThreadContextContributor.Snapshot> capture() {
        return contributors.stream().map(ThreadContextContributor::capture).toList();
    }

    public void runCleared(Runnable action) {
        runWith(contributors.stream().map(ThreadContextContributor::empty).toList(), action);
    }

    public void runWith(List<ThreadContextContributor.Snapshot> snapshots, Runnable action) {
        if (snapshots.size() != contributors.size()) {
            throw new IllegalArgumentException("Context snapshot does not match registered contributors");
        }
        runWith(0, snapshots, action);
    }

    private void runWith(int index, List<ThreadContextContributor.Snapshot> snapshots, Runnable action) {
        if (index == contributors.size()) {
            action.run();
            return;
        }
        ThreadContextContributor.Snapshot previous = contributors.get(index).capture();
        try {
            snapshots.get(index).restore();
            runWith(index + 1, snapshots, action);
        } finally {
            previous.restore();
        }
    }
}
