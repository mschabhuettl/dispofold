-- A corrections file loaded in Dispofold (Einstellungen, Daten): the base of the corrections until an import through
-- an import source has applied it, as if an earlier import had. One row at most; independent of the dataset row, so
-- that it can be loaded before the first import.
create table loaded_corrections (
    id int primary key,
    doc text not null,
    loaded timestamp not null
);
