package com.tyron.completion.xml.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.collect.ListMultimap;
import com.tyron.builder.compiler.manifest.configuration.Configurable;
import com.tyron.builder.compiler.manifest.configuration.FolderConfiguration;
import com.tyron.builder.compiler.manifest.resources.ResourceType;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.Module;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.model.DrawableKind;
import com.tyron.completion.xml.XmlRepository;
import com.tyron.completion.xml.repository.ResourceItem;
import com.tyron.completion.xml.repository.ResourceRepository;
import com.tyron.completion.xml.repository.api.AttrResourceValue;
import com.tyron.completion.xml.repository.api.AttributeFormat;
import com.tyron.completion.xml.repository.api.ResourceNamespace;
import com.tyron.completion.xml.repository.api.ResourceUrl;
import com.tyron.completion.xml.repository.api.ResourceValue;

import org.eclipse.lemminx.dom.DOMAttr;
import org.eclipse.lemminx.dom.DOMElement;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AttributeValueUtils {

    private static final FolderConfiguration DEFAULT = FolderConfiguration.createDefault();

    public static void addValueItems(@NonNull Project project, @NonNull Module module,
                                     @NonNull String prefix,
                                     @NonNull XmlRepository repo, @NonNull DOMAttr attr,
                                     @NonNull ResourceNamespace attrNamespace,
                                     @NonNull ResourceNamespace appNamespace,
                                     @NonNull CompletionList.Builder list) {
        ResourceRepository repository = repo.getRepository();
        AttrResourceValue attribute =
                AttributeProcessingUtil.getLayoutAttributeFromNode(repository, attr.getOwnerElement(),
                                                                   attr.getLocalName(), attrNamespace);
        if (attribute == null) {
            // attribute is not found
            return;
        }

        if (prefix.startsWith("@")) {
            String resourceType = getResourceType(prefix);
            if (resourceType == null) {
                return;
            }

            ResourceNamespace.Resolver resolver =
                    namespacePrefix -> DOMUtils.lookupPrefix(attr, namespacePrefix);

            ResourceNamespace namespace;
            if (resourceType.contains(":")) {
                int index = resourceType.indexOf(':');
                String packagePrefix = resourceType.substring(0 , index);
                resourceType = resourceType.substring(index + 1);
                namespace = ResourceNamespace.fromPackageName(packagePrefix);
            } else {
                namespace = appNamespace;
            }

            ResourceType fromTag = ResourceType.fromXmlTagName(resourceType);
            if (fromTag == null) {
                return;
            }

            List<ResourceValue> items = repository.getResources(namespace, fromTag)
                    .asMap()
                    .values()
                    .stream()
                    .map(AttributeValueUtils::getApplicableValue)
                    .map(it -> it != null ? it.getResourceValue() : null)
                    .collect(Collectors.toList());

            for (ResourceValue value : items) {
                if (!namespace.equals(value.getNamespace())) {
                    continue;
                }
                if (value.getResourceType().getName().startsWith(resourceType)) {
                    String label = value.asReference().getRelativeResourceUrl(appNamespace, resolver).toString();
                    CompletionItem item = CompletionItem.create(label, "Value",
                                                                  label);
                    item.iconKind = DrawableKind.LocalVariable;
                    list.addItem(item);
                }
            }
        }
    }

    @Nullable
    private static String getResourceType(String declaration) {
        if (!declaration.startsWith("@")) {
            return null;
        }
        if (declaration.contains("/")) {
            return declaration.substring(1, declaration.indexOf('/'));
        }
        return declaration.substring(1);
    }

    @Nullable
    public static ResourceItem getApplicableValue(Collection<ResourceItem> items) {
        Map<Configurable, ResourceItem> map = new HashMap<>();
        for (ResourceItem item : items) {
            FolderConfiguration configuration = item.getConfiguration();
            map.put(() -> configuration, item);
        }
        Configurable matching = DEFAULT.findMatchingConfigurable(map.keySet());
        if (matching == null) {
            return null;
        }
        return map.get(matching);
    }

    private static List<ResourceType> getMatchingTypes(AttrResourceValue attrResourceValue) {
        return attrResourceValue.getFormats().stream()
                .flatMap(it -> it.getMatchingTypes().stream())
                .collect(Collectors.toList());
    }
}
