package com.wannian.server.app.journal;

import com.wannian.server.kernel.journal.RunJournal;
import com.wannian.server.kernel.journal.RunJournalEntry;
import java.util.List;
import java.util.Objects;

/** 将一条条目扇出到多个 sink；任一失败不影响其它。 */
public final class CompositeRunJournal implements RunJournal {

    private static final System.Logger LOG = System.getLogger(CompositeRunJournal.class.getName());

    private final List<RunJournal> sinks;

    public CompositeRunJournal(List<RunJournal> sinks) {
        this.sinks = List.copyOf(Objects.requireNonNull(sinks, "sinks"));
    }

    @Override
    public void append(RunJournalEntry entry) {
        Objects.requireNonNull(entry, "entry");
        for (RunJournal sink : sinks) {
            try {
                sink.append(entry);
            } catch (RuntimeException ex) {
                LOG.log(
                        System.Logger.Level.WARNING,
                        () ->
                                "RunJournal sink 失败 sink="
                                        + sink.getClass().getSimpleName()
                                        + " turn="
                                        + entry.turnId()
                                        + " kind="
                                        + entry.kind()
                                        + ": "
                                        + ex.getMessage());
            }
        }
    }
}
