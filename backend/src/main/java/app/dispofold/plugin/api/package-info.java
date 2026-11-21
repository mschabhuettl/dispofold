/**
 * The plugin API: the only Dispofold classes a plugin may use, besides the data model ({@code app.dispofold.model}).
 * How to write a plugin and the rules it follows: docs/plugins.md.
 * <ul>
 * <li>{@link app.dispofold.plugin.api.Plugin}: the plugin itself, a CDI bean ({@code @ApplicationScoped}).</li>
 * <li>Routes: JAX-RS resources below {@code /api/plugins/<id>}, with {@code @RolesAllowed("planner")}, or
 * {@code @RolesAllowed({"planner", "viewer"})} for what readers may see. While the plugin is off or hidden every
 * route answers 404.</li>
 * <li>{@link app.dispofold.plugin.api.PluginHost}: settings, the cumulative corrections, file access.</li>
 * <li>{@link app.dispofold.plugin.api.ImportSource}: a dataset for the import of Dispofold.</li>
 * <li>{@link app.dispofold.plugin.api.PluginErrors}: error answers the front end can show.</li>
 * </ul>
 */
package app.dispofold.plugin.api;
