-- The master data as last imported (the raw imported dataset without periods): the baseline for the changes made
-- in Dispofold since, exported as a corrections file or carried over to a new import. Null for installs upgraded
-- from older versions until their next import.
alter table dataset add column baseline text;
