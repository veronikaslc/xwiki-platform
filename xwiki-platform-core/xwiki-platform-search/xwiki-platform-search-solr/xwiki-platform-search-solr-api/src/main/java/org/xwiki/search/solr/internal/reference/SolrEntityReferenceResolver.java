/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.search.solr.internal.reference;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang.LocaleUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.solr.common.SolrDocument;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceResolver;
import org.xwiki.search.solr.internal.api.FieldUtils;

/**
 * Component used to extract an {@link EntityReference} from a {@link SolrDocument}.
 * 
 * @version $Id$
 * @since 7.2M1
 */
@Component
@Singleton
public class SolrEntityReferenceResolver implements EntityReferenceResolver<SolrDocument>
{
    @Inject
    @Named("explicit")
    private EntityReferenceResolver<EntityReference> explicitReferenceEntityReferenceResolver;

    @Inject
    private EntityReferenceResolver<String> defaultStringEntityReferenceResolver;

    @Override
    public EntityReference resolve(SolrDocument solrDocument, EntityType type, Object... parameters)
    {
        EntityReference solrEntityReference = getEntityReference(solrDocument);
        return this.explicitReferenceEntityReferenceResolver.resolve(solrEntityReference, type, parameters);
    }

    private EntityReference getEntityReference(SolrDocument solrDocument)
    {
        EntityReference wikiReference = getWikiReference(solrDocument);
        EntityReference spaceReference = getSpaceReference(solrDocument, wikiReference);
        EntityReference documentReference = getDocumentReferenceWithLocale(solrDocument, spaceReference);

        EntityType entityType = EntityType.valueOf((String) solrDocument.get(FieldUtils.TYPE));
        switch (entityType) {
            case ATTACHMENT:
                return getAttachmentReference(solrDocument, documentReference);
            case OBJECT:
                return getObjectReference(solrDocument, documentReference);
            case OBJECT_PROPERTY:
                EntityReference objectReference = getObjectReference(solrDocument, documentReference);
                return getObjectPropertyReference(solrDocument, objectReference);
            default:
                return documentReference;
        }
    }

    private EntityReference getWikiReference(SolrDocument solrDocument)
    {
        return new EntityReference((String) solrDocument.get(FieldUtils.WIKI), EntityType.WIKI);
    }

    private EntityReference getSpaceReference(SolrDocument solrDocument, EntityReference parent)
    {
        EntityReference actualParent = parent;
        String docParentPath = (String) solrDocument.get(FieldUtils.DOCUMENT_PARENT_PATH);
        if (!StringUtils.isEmpty(docParentPath)) {
            actualParent = this.defaultStringEntityReferenceResolver.resolve(docParentPath, EntityType.SPACE, parent);
        }
        String lastSpaceName = (String) solrDocument.get(FieldUtils.DOCUMENT_NAME);
        return new EntityReference(lastSpaceName, EntityType.SPACE, actualParent);
    }

    private EntityReference getDocumentReferenceWithLocale(SolrDocument solrDocument, EntityReference parent)
    {
        EntityReference documentReference = getDocumentReference(solrDocument, parent);
        String localeString = (String) solrDocument.get(FieldUtils.DOCUMENT_LOCALE);
        if (!StringUtils.isEmpty(localeString)) {
            documentReference = new DocumentReference(documentReference, LocaleUtils.toLocale(localeString));
        }
        return documentReference;
    }

    private EntityReference getDocumentReference(SolrDocument solrDocument, EntityReference parent)
    {
        Boolean docFinal = (Boolean) solrDocument.get(FieldUtils.DOCUMENT_FINAL);
        if (docFinal != null && docFinal) {
            return new EntityReference(parent.getName(), EntityType.DOCUMENT, parent.getParent());
        } else {
            return this.defaultStringEntityReferenceResolver.resolve("", EntityType.DOCUMENT, parent);
        }
    }

    private EntityReference getAttachmentReference(SolrDocument solrDocument, EntityReference parent)
    {
        return new EntityReference((String) solrDocument.get(FieldUtils.FILENAME), EntityType.ATTACHMENT, parent);
    }

    private EntityReference getObjectReference(SolrDocument solrDocument, EntityReference parent)
    {
        String classReference = (String) solrDocument.get(FieldUtils.CLASS);
        Integer objectNumber = (Integer) solrDocument.get(FieldUtils.NUMBER);
        return new EntityReference(String.format("%s[%s]", classReference, objectNumber), EntityType.OBJECT, parent);
    }

    private EntityReference getObjectPropertyReference(SolrDocument solrDocument, EntityReference parent)
    {
        String propertyName = (String) solrDocument.get(FieldUtils.PROPERTY_NAME);
        return new EntityReference(propertyName, EntityType.OBJECT_PROPERTY, parent);
    }
}
