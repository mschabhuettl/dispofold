-- Plugins (docs/plugins.md): switched on by a planner, off by default. The plugin's own settings are JSON with the
-- version of their form, so that a plugin can migrate them; switching a plugin off keeps them.
create table plugin_state (
    id varchar(64) primary key,
    enabled boolean not null,
    settings_version int not null,
    settings text not null
);

-- Folders the user of the desktop program picked for plugins to read (the server's come from its configuration).
create table picked_root (
    path varchar(4000) primary key
);
