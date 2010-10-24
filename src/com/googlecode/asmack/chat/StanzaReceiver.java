package com.googlecode.asmack.chat;

import java.io.IOException;
import java.io.StringReader;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.net.Uri.Builder;
import android.text.TextUtils;
import android.util.Log;

import com.googlecode.asmack.Attribute;
import com.googlecode.asmack.Stanza;

/**
 * StanzaReceiver listens to all events, filters &lt;message &gt> stanzas and
 * writes them to the database.
 */
public class StanzaReceiver extends BroadcastReceiver {

    /**
     * The logging tag, StanzaReceiver.
     */
    private static final String TAG = StanzaReceiver.class.getSimpleName();

    /**
     * The internal database instance.
     */
    private SQLiteDatabase database = null;

    /**
     * A dom builder factory for the message payload.
     */
    private DocumentBuilderFactory documentBuilderFactory;

    /**
     * A concrete dom builder.
     */
    private DocumentBuilder documentBuilder;

    /**
     * Create a new StanzaReceiver by initializing the xml document builder
     * factory.
     */
    public StanzaReceiver() {
        documentBuilderFactory = DocumentBuilderFactory.newInstance();
        // XMPP requires namespace awareness
        documentBuilderFactory.setNamespaceAware(true);
        // slightly normalize
        documentBuilderFactory.setCoalescing(true);
        documentBuilderFactory.setIgnoringComments(true);
        documentBuilderFactory.setIgnoringElementContentWhitespace(false);
        // No includes / outbound references
        // documentBuilderFactory.setXIncludeAware(false);
        documentBuilderFactory.setExpandEntityReferences(false);
        documentBuilderFactory.setValidating(false);
        try {
            documentBuilder = documentBuilderFactory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        };
    }

    /**
     * Receive a single stanza, check for the message type, and write it to the
     * database.
     * @param context The receiver context.
     * @param intent The stanza intent.
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!intent.hasExtra("stanza")) {
            return;
        }
        Stanza stanza = intent.getParcelableExtra("stanza");
        if (!"message".equals(stanza.getName())) {
            return;
        }
        Attribute from = stanza.getAttribute("from");
        if (from == null) {
            return;
        }
        String fromJid = XMPPUtils.getBareJid(from.getValue());
        if (TextUtils.isEmpty(fromJid)) {
            return;
        }
        String xml = stanza.getXml();
        String body = null;
        try {
            Document document =
                documentBuilder.parse(new InputSource(new StringReader(xml)));
            NodeList nodes = document.getDocumentElement().getChildNodes();
            for (int i = nodes.getLength() - 1; i >= 0; i--) {
                Node item = nodes.item(i);
                if (!"body".equals(item.getLocalName())) {
                    continue;
                }
                if (!"jabber:client".equals(item.getNamespaceURI())) {
                    continue;
                }
                body = item.getTextContent();
            }
        } catch (SAXException e) {
            /* Would be a bug in the XMPP core service */
            return;
        } catch (IOException e) {
            /* Most likely impossible */
            return;
        }
        if (body == null) {
            return;
        }
        Log.d(TAG, "MESSAGE from " + from.getValue());
        if (database == null) {
            database = Database.getDatabase(context, null);
        }
        ContentValues values = new ContentValues();
        values.put("ts", System.currentTimeMillis());
        values.put("via", XMPPUtils.getBareJid(stanza.getVia()));
        values.put("jid", fromJid);
        values.put("dst", stanza.getVia());
        values.put("src", fromJid);
        values.put("msg", body);
        database.insert("msg", "_id", values);
        Builder builder = new Uri.Builder();
        builder.scheme("content");
        builder.authority("jabber-chat-db");
        builder.appendPath(XMPPUtils.getBareJid(stanza.getVia()));
        builder.appendPath(fromJid);
        context.getContentResolver().notifyChange(builder.build(), null);
    }

}
