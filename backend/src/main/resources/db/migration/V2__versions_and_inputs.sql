-- Optimistic versioning of planning months: a save must name the revision it was based on.
alter table period add column revision bigint default 0 not null;

-- Fingerprints of the inputs of the last solve and of the previous month's plan at that time; a plan whose inputs
-- changed afterwards is shown as stale.
alter table plan add column input_hash varchar(64);
alter table plan add column previous_hash varchar(64);
