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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.solr.client.solrj.util.ClientUtils;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.AttachmentReference;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceProvider;
import org.xwiki.search.solr.internal.api.FieldUtils;
import org.xwiki.search.solr.internal.api.SolrIndexerException;

import com.google.common.collect.Iterables;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiAttachment;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.BaseObjectReference;

/**
 * Resolve document references.
 * 
 * @version $Id$
 * @since 5.1M2
 */
@Component
@Named("document")
@Singleton
public class DocumentSolrReferenceResolver extends AbstractSolrReferenceResolver
{
    /**
     * Used to resolve object references.
     */
    @Inject
    @Named("object")
    private Provider<SolrReferenceResolver> objectResolverProvider;

    /**
     * Used to resolve space references.
     */
    @Inject
    @Named("space")
    private Provider<SolrReferenceResolver> spaceResolverProvider;

    /**
     * Used to resolve attachment references.
     */
    @Inject
    @Named("attachment")
    private Provider<SolrReferenceResolver> attachmentResolverProvider;

    @Inject
    private EntityReferenceProvider defaultEntityReferenceProvider;

    @Override
    public List<EntityReference> getReferences(EntityReference reference) throws SolrIndexerException
    {
        List<EntityReference> result = new ArrayList<EntityReference>();

        XWikiContext xcontext = this.xcontextProvider.get();

        DocumentReference documentReference = new DocumentReference(reference);

        if (xcontext.getWiki().exists(documentReference, xcontext)) {
            // Document itself
            result.add(documentReference);

            // FIXME: Assumption - Only original documents contain objects and attachments, because objects are
            // not translatable.
            // http://jira.xwiki.org/browse/XWIKI-69 is the long standing issue on which the second assumption relies.
            if (documentReference.getLocale() == null || documentReference.getLocale().equals(Locale.ROOT)) {
                XWikiDocument document;
                try {
                    document = getDocument(documentReference);
                } catch (Exception e) {
                    throw new SolrIndexerException(String.format("Failed to get document [%s]", documentReference), e);
                }

                // Document translations
                List<Locale> translatedLocales;
                try {
                    translatedLocales = document.getTranslationLocales(xcontext);
                } catch (XWikiException e) {
                    throw new SolrIndexerException(String.format("Failed to get document [%s] translations",
                        documentReference), e);
                }

                for (Locale translatedLocale : translatedLocales) {
                    DocumentReference translatedDocumentReference =
                        new DocumentReference(documentReference, translatedLocale);
                    result.add(translatedDocumentReference);
                }

                // Attachments
                addAttachmentsReferences(document, result);

                // Objects
                addObjectsReferences(document, result);
            }
        }

        return result;
    }

    /**
     * @param document the document
     * @param result the list to add reference to
     */
    private void addAttachmentsReferences(XWikiDocument document, List<EntityReference> result)
    {
        List<XWikiAttachment> attachments = document.getAttachmentList();
        for (XWikiAttachment attachment : attachments) {
            AttachmentReference attachmentReference = attachment.getReference();

            try {
                Iterables.addAll(result, this.attachmentResolverProvider.get().getReferences(attachmentReference));
            } catch (Exception e) {
                this.logger.error("Failed to resolve references for attachment [" + attachmentReference + "]", e);
            }
        }
    }

    /**
     * @param document the document
     * @param result the list to add reference to
     */
    private void addObjectsReferences(XWikiDocument document, List<EntityReference> result)
    {
        for (Entry<DocumentReference, List<BaseObject>> entry : document.getXObjects().entrySet()) {
            List<BaseObject> objects = entry.getValue();
            for (BaseObject object : objects) {
                if (object != null) {
                    BaseObjectReference objectReference = object.getReference();

                    try {
                        Iterables.addAll(result, this.objectResolverProvider.get().getReferences(objectReference));
                    } catch (Exception e) {
                        this.logger.error("Failed to resolve references for object [" + objectReference + "]", e);
                    }
                }
            }
        }
    }

    @Override
    public String getId(EntityReference reference) throws SolrIndexerException
    {
        DocumentReference documentReference = new DocumentReference(reference);

        // Document IDs also contain the locale code to differentiate between them.
        // Objects, attachments, etc. don't need this because the only thing that is translated in an XWiki document
        // right now is the document title and content. Objects and attachments are not translated.
        Locale locale = documentReference.getLocale();
        if (locale == null) {
            locale = Locale.ROOT;
        }

        return super.getId(reference) + FieldUtils.USCORE + locale;
    }

    @Override
    public String getQuery(EntityReference reference) throws SolrIndexerException
    {
        EntityReference documentReference = reference.extractReference(EntityType.DOCUMENT);
        EntityReference defaultDocumentReference =
            this.defaultEntityReferenceProvider.getDefaultReference(EntityType.DOCUMENT);
        boolean docFinal = true;
        String documentName = documentReference.getName();
        EntityReference documentParentReference = documentReference.extractReference(EntityType.SPACE);
        if (Objects.equals(documentReference.getName(), defaultDocumentReference.getName())) {
            docFinal = false;
            documentName = documentParentReference.getName();
            documentParentReference = documentParentReference.getParent();
        }

        StringBuilder builder = new StringBuilder();

        builder.append(this.spaceResolverProvider.get().getQuery(documentParentReference));

        builder.append(QUERY_AND);

        builder.append(FieldUtils.DOCUMENT_NAME_EXACT);
        builder.append(':');
        builder.append(ClientUtils.escapeQueryChars(documentName));

        builder.append(QUERY_AND);

        builder.append(FieldUtils.DOCUMENT_FINAL);
        builder.append(':');
        builder.append(docFinal);

        return builder.toString();
    }
}
