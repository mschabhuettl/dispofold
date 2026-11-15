-- Master data edited in Dispofold: every save of the master data increments the revision (optimistic versioning
-- like the planning months); imported_revision is the revision of the last import, so a later revision means the
-- master data were changed in Dispofold since then. ids holds the counters of generated ids and the retired
-- short codes as JSON.
alter table dataset add column revision bigint default 0 not null;
alter table dataset add column imported_revision bigint default 0 not null;
alter table dataset add column ids text;
