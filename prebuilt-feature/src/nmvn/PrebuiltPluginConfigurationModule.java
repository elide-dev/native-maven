package nmvn;

import com.google.inject.Binder;
import com.google.inject.Module;
import com.google.inject.name.Names;
import org.apache.maven.api.model.Plugin;
import org.apache.maven.api.xml.XmlNode;
import org.apache.maven.internal.xml.XmlPlexusConfiguration;
import org.codehaus.plexus.configuration.PlexusConfiguration;

/**
 * Public replica of maven-core's package-private {@code PluginConfigurationModule}: binds the
 * plugin's {@code <configuration>} under the plugin key so mojos can inject it. Needed because the
 * sidecar's publication path assembles the same Guice modules stock discovery would, but cannot
 * reach the package-private original.
 */
final class PrebuiltPluginConfigurationModule implements Module {

    private final Plugin plugin;

    PrebuiltPluginConfigurationModule(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void configure(Binder binder) {
        if (plugin.getKey() != null) {
            XmlNode configuration = plugin.getConfiguration();
            if (configuration == null) {
                configuration = XmlNode.newInstance("configuration");
            }
            binder.bind(XmlNode.class)
                    .annotatedWith(Names.named(plugin.getKey()))
                    .toInstance(configuration);
            binder.bind(PlexusConfiguration.class)
                    .annotatedWith(Names.named(plugin.getKey()))
                    .toInstance(XmlPlexusConfiguration.toPlexusConfiguration(configuration));
        }
    }
}
