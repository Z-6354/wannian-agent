-- Fence delayed memory drafts against subject changes made after their input snapshot.
CREATE TABLE memory_subject_generation (
    companion_identity_id TEXT NOT NULL,
    subject_key TEXT NOT NULL,
    generation INTEGER NOT NULL CHECK (generation > 0),
    PRIMARY KEY (companion_identity_id, subject_key)
);

-- Existing subjects start at generation 1; absent subjects use generation 0.
INSERT INTO memory_subject_generation (companion_identity_id, subject_key, generation)
SELECT companion_identity_id, subject_key, 1
FROM memory_record
GROUP BY companion_identity_id, subject_key;
