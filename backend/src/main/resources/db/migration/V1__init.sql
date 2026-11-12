-- Documents are stored as JSON text in the format of docs/datenformat.md.
-- The schema works on PostgreSQL (server) and H2 (desktop).

-- Master data of the last import, without periods (single row).
create table dataset (
    id int primary key check (id = 1),
    doc text not null,
    updated timestamp with time zone default current_timestamp not null
);

-- Absences, wishes and fixed duties per planning month.
create table period (
    ym varchar(7) primary key,
    doc text not null
);

-- One plan per month; hard_violations and elapsed_millis belong to the last solve.
create table plan (
    ym varchar(7) primary key,
    doc text not null,
    hard_violations int default 0 not null,
    elapsed_millis bigint default 0 not null
);

-- Counters of finalized months; they override imported history for the same person and month.
create table history (
    person_id varchar(64) not null,
    ym varchar(7) not null,
    doc text not null,
    primary key (person_id, ym)
);

-- Planning rules and weights (single row).
create table rules (
    id int primary key check (id = 1),
    doc text not null
);
