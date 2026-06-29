package nmvn;

import javax.inject.Named;
import javax.inject.Singleton;

import java.util.List;

import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.DefaultPluginDescriptorCache;
import org.apache.maven.plugin.InvalidPluginDescriptorException;
import org.apache.maven.plugin.PluginDescriptorParsingException;
import org.apache.maven.plugin.PluginResolutionException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.sisu.Priority;

/**
 * Sidecar override of Maven's plugin descriptor cache ({@code @Priority} outranks the default
 * binding; discovered via this jar's META-INF/sisu index). This is the ROUTING seam for prebuilt
 * plugins: stock {@code DefaultMavenPluginManager.getPluginDescriptor} consults this cache before
 * resolving anything, so serving the baked descriptor here means no repository resolution and no
 * runtime plugin.xml parsing — with ZERO maven-core changes.
 *
 * <p>Serves a CLONE per get, exactly like the default cache (clone-on-get keeps descriptors
 * realm-less until setupPluginRealm and avoids shared mutable state; the shallow MojoDescriptor
 * clone preserves the build-time-pinned implementation classes). Non-prebuilt plugins delegate to
 * the stock implementation unchanged.
 */
@Named
@Singleton
@Priority(10)
public class PrebuiltPluginDescriptorCache extends DefaultPluginDescriptorCache {

    public PrebuiltPluginDescriptorCache() {
        org.slf4j.LoggerFactory.getLogger(getClass())
                .info("nmvn: descriptor cache override active ({})", PrebuiltPluginRealms.STATUS);
    }

    /** Key marking a plugin that is served from the baked registry. */
    private static final class PrebuiltKey implements Key {
        final PrebuiltPluginRealms.Prebuilt prebuilt;

        PrebuiltKey(PrebuiltPluginRealms.Prebuilt prebuilt) {
            this.prebuilt = prebuilt;
        }
    }

    @Override
    public Key createKey(Plugin plugin, List<RemoteRepository> repositories, RepositorySystemSession session) {
        PrebuiltPluginRealms.Prebuilt prebuilt =
                PrebuiltPluginRealms.match(plugin.getGroupId(), plugin.getArtifactId(), plugin.getVersion());
        if (prebuilt != null) {
            return new PrebuiltKey(prebuilt);
        }
        return super.createKey(plugin, repositories, session);
    }

    @Override
    public PluginDescriptor get(Key key) {
        if (key instanceof PrebuiltKey prebuiltKey) {
            return new PluginDescriptor(prebuiltKey.prebuilt.descriptor);
        }
        return super.get(key);
    }

    @Override
    public PluginDescriptor get(Key key, PluginDescriptorSupplier supplier)
            throws PluginResolutionException, PluginDescriptorParsingException, InvalidPluginDescriptorException {
        if (key instanceof PrebuiltKey prebuiltKey) {
            // never invoke the supplier: it would resolve the plugin from the repository
            return new PluginDescriptor(prebuiltKey.prebuilt.descriptor);
        }
        return super.get(key, supplier);
    }

    @Override
    public void put(Key key, PluginDescriptor descriptor) {
        if (key instanceof PrebuiltKey) {
            return; // baked entries are immutable; nothing to store
        }
        super.put(key, descriptor);
    }
}
