-- The form of the stored weights: 1 for rules saved before version 0.28, whose weights that stand at an old default
-- move to the new one on the first read (see ConstraintCatalog.upgradeWeights); rules saved from now on are 2.
alter table rules add column weights_version int default 2 not null;
update rules set weights_version = 1;
