/*
 *   Copyright 2012 OSBI Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.saiku.repository;


import org.apache.commons.io.FileUtils;
import org.apache.jackrabbit.commons.JcrUtils;
import org.apache.jackrabbit.core.TransientRepository;
import org.saiku.database.dto.MondrianSchema;
import org.saiku.datasources.connection.RepositoryFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.*;
import javax.jcr.nodetype.NodeTypeManager;
import javax.jcr.nodetype.NodeTypeTemplate;
import javax.jcr.nodetype.PropertyDefinitionTemplate;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import javax.jcr.query.QueryResult;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * JackRabbit JCR Repository Manager for Saiku.
 */
public class JackRabbitRepositoryManager implements IRepositoryManager {

    private static final Logger log = LoggerFactory.getLogger(JackRabbitRepositoryManager.class);
    private static JackRabbitRepositoryManager ref;
    private Repository repository;
    private Session session;
    private Node root;

    private JackRabbitRepositoryManager() {

    }

    public static synchronized JackRabbitRepositoryManager getJackRabbitRepositoryManager() {
        if (ref == null)
            // it's ok, we can call this constructor
            ref = new JackRabbitRepositoryManager();
        return ref;
    }

    public Object clone()
            throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
        // that'll teach 'em
    }

    public void init() {

    }

    public void login() throws RepositoryException {
        session = repository.login(
                new SimpleCredentials("admin", "admin".toCharArray()));
    }


    public boolean start() throws RepositoryException {
        if (session == null) {
            log.info("starting repo");
            repository = new TransientRepository("../jackrabbit2.properties", "./target/tmp");
            log.info("repo started");
            log.info("logging in");
            login();
            log.info("logged in");
            root = session.getRootNode();
            JcrUtils.getOrAddFolder(root, "homes");
            JcrUtils.getOrAddFolder(root, "datasources");

            root.getSession().save();
            createFiles();
            createNamespace();
            createSchemas();
            createDataSources();
            session.save();
            log.info("node added");
        }
        return true;

    }

    public void createUser(String u) throws RepositoryException {
        login();
        Node node = root.getNode("homes").addNode("home:" + u, "nt:folder");
        node.setProperty("type", "homedirectory");
        node.setProperty("user", u);
        node.getSession().save();
    }

    public javax.jcr.NodeIterator getHomeFolders() throws RepositoryException {
        //login();
        Node homes = root.getNode("homes");
        return homes.getNodes();
    }

    public Node getHomeFolder(String path) throws RepositoryException {
        return root.getNode("homes").getNode("home:" + path);
    }

    public Node getFolder(String user, String directory) throws RepositoryException {
        return getHomeFolder(user).getNode(directory);
    }

    public void shutdown() {
        ((TransientRepository) repository).shutdown();
        String repositoryLocation = ((TransientRepository) repository).getHomeDir();
        try {
            FileUtils.deleteDirectory(new File(repositoryLocation));
        } catch (final IOException e) {
            System.out.println(e.getLocalizedMessage());
            //TODO FIX
        }
        repository = null;
        session = null;
    }

    public boolean createFolder(String username, String folder) throws RepositoryException {
        Node userfolder = getHomeFolder(username);
        String[] path = folder.split("/");
        Node nest = null;
        for (String p : path) {
            if (nest == null) {
                nest = userfolder.addNode(p);
            } else {
                nest.addNode(p);
            }

        }

        userfolder.getSession().save();
        return true;
    }

    public boolean deleteFolder(String folder) throws RepositoryException {
        Node node = JcrUtils.getNodeIfExists(root, folder);
        node.remove();
        node.getSession().save();
        return true;
    }

    public void deleteRepository() throws RepositoryException {
        while (root.getNodes().hasNext()) {
            root.getNodes().nextNode().remove();
        }
    }

    public boolean moveFolder(String user, String folder, String source, String target) throws RepositoryException {
        Node root = getHomeFolder(user).getNode(source + "/" + folder);

        if (target == null) {
            //session.getWorkspace().move(root.getPath(), root.getSession().getRootNode().getPath()+"/homes/home:"+user+"/"+folder);
            root.getSession().move(root.getPath(), getHomeFolder(user).getPath() + "/" + root.getName());
            root.getSession().save();
        } else {
            root.getSession().move(root.getPath(), getHomeFolder(user).getPath());
            root.getSession().save();
        }

        return true;
    }

    public Node saveFile(Object file, String path, String user, String type, List<String> roles) throws RepositoryException {
        int pos = path.lastIndexOf("/");
        String filename = "./" + path.substring(pos + 1, path.length());
        Node n = getFolder(path.substring(0, pos));
        Node resNode = n.addNode(filename, "nt:file");
        if(type.equals("nt:saikufiles")){
            resNode.addMixin("nt:saikufiles");
        }
        else if(type.equals("nt:mondrianschema")){
            resNode.addMixin("nt:mondrianschema");
        }
        else if(type.equals("nt:olapdatasource")){
            resNode.addMixin("nt:olapdatasource");
        }
        Node contentNode = resNode.addNode("jcr:content", "nt:resource");

        //resNode.setProperty ("jcr:mimeType", "text/plain");
        //resNode.setProperty ("jcr:encoding", "utf8");
        contentNode.setProperty("jcr:data", (String) file);
        /*Calendar lastModified = Calendar.getInstance ();
        lastModified.setTimeInMillis (new Date().getTime());
        resNode.setProperty ("jcr:lastModified", lastModified);*/
        resNode.getSession().save();
        return resNode;
    }

    public String getFile(String s, String username) throws RepositoryException {
        return getFolder(s).getNodes("jcr:content").nextNode().getProperty("jcr:data").getString();
    }

    public List<MondrianSchema> getAllSchema() throws RepositoryException {
        QueryManager qm = session.getWorkspace().getQueryManager();
        String sql = "SELECT * FROM [nt:mondrianschema]";
        Query query = qm.createQuery(sql, Query.JCR_SQL2);

        QueryResult res = query.execute();

        NodeIterator node = res.getNodes();

        List<MondrianSchema> l = new ArrayList<MondrianSchema>();
        while (node.hasNext()) {
            Node n = node.nextNode();
            String p = n.getPath();

            MondrianSchema m = new MondrianSchema();
            m.setName(n.getName());
            m.setPath(p);

            l.add(m);

        }
        return l;
    }

    public Node getAllFiles() throws RepositoryException {
        return root;
    }

    public void deleteFile(String datasourcePath) {
        Node n;
        try {
            n = getFolder(datasourcePath);
            n.remove();
        } catch (RepositoryException e) {
            e.printStackTrace();
        }

    }

    public List<DataSource> getAllDataSources() throws RepositoryException {
        QueryManager qm = session.getWorkspace().getQueryManager();
        String sql = "SELECT * FROM [nt:olapdatasource]";
        Query query = qm.createQuery(sql, Query.JCR_SQL2);

        QueryResult res = query.execute();

        NodeIterator node = res.getNodes();

        List<DataSource> ds = new ArrayList<DataSource>();
        while (node.hasNext()) {
            Node n = node.nextNode();
            JAXBContext jaxbContext = null;
            Unmarshaller jaxbMarshaller = null;
            try {
                jaxbContext = JAXBContext.newInstance(DataSource.class);
            } catch (JAXBException e) {
                e.printStackTrace();
            }
            try {
                jaxbMarshaller = jaxbContext != null ? jaxbContext.createUnmarshaller() : null;
            } catch (JAXBException e) {
                e.printStackTrace();
            }
            InputStream stream = new ByteArrayInputStream(n.getProperty("jcr:data").getString().getBytes());
            DataSource d = null;
            try {
                d = (DataSource) (jaxbMarshaller != null ? jaxbMarshaller.unmarshal(stream) : null);
            } catch (JAXBException e) {
                e.printStackTrace();
            }

            if (d != null) {
                d.setPath(n.getPath());
            }
            ds.add(d);

        }

        return ds;
    }

    public void saveDataSource(DataSource ds, String path, String user) throws RepositoryException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            JAXBContext jaxbContext = JAXBContext.newInstance(DataSource.class);
            Marshaller jaxbMarshaller = jaxbContext.createMarshaller();

            // output pretty printed
            jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);

            jaxbMarshaller.marshal(ds, baos);


        } catch (JAXBException e) {
            e.printStackTrace();
        }

        int pos = path.lastIndexOf("/");
        String filename = "./" + path.substring(pos + 1, path.length());
        Node n = getFolder(path.substring(0, pos));
        Node resNode = n.addNode(filename, "nt:file");

        resNode.addMixin("nt:olapdatasource");

        Node contentNode = resNode.addNode("jcr:content", "nt:resource");

        //resNode.setProperty ("jcr:mimeType", "text/plain");
        //resNode.setProperty ("jcr:encoding", "utf8");
        contentNode.setProperty("jcr:data", baos.toString());
        /*Calendar lastModified = Calendar.getInstance ();
        lastModified.setTimeInMillis (new Date().getTime());
        resNode.setProperty ("jcr:lastModified", lastModified);*/
        resNode.getSession().save();

    }

    public byte[] exportRepository() throws RepositoryException, IOException {
        final ByteArrayOutputStream os2 = new ByteArrayOutputStream();
        final OutputStream os = new ByteArrayOutputStream();
        session.exportDocumentView("/", os, false, false);
        ZipOutputStream zs = new ZipOutputStream(os2);
        ZipEntry e = new ZipEntry("backup.xml");
        zs.putNextEntry(e);
        zs.write(os.toString().getBytes());
        zs.closeEntry();
        zs.close();
        return os2.toByteArray();
    }

    public void restoreRepository(String xml) throws RepositoryException, IOException {
        InputStream stream = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        session.importXML("/", stream, ImportUUIDBehavior.IMPORT_UUID_COLLISION_REPLACE_EXISTING);
    }

    public RepositoryFile getFile(String fileUrl) {
        Node n = null;
        try {
            n = getFolder(fileUrl);
        } catch (RepositoryException e) {
            e.printStackTrace();
        }

        try {
            return new RepositoryFile(n != null ? n.getName() : null, null, null, fileUrl);
        } catch (RepositoryException e) {
            e.printStackTrace();
        }

        return null;
    }


    public Node getFolder(String path) throws RepositoryException {
        return session.getNode(path);
    }

    public Repository getRepository() {
        return repository;
    }

    public void setRepository(Repository repository) {
        this.repository = repository;
    }

    public void createNamespace() throws RepositoryException {
        NamespaceRegistry ns = session.getWorkspace().getNamespaceRegistry();

        if (!Arrays.asList(ns.getPrefixes()).contains("home")) {
            ns.registerNamespace("home", "http://www.meteorite.bi/namespaces/home");
        }
    }

    public void createDataSources() throws RepositoryException {

        NodeTypeManager manager = session.getWorkspace().getNodeTypeManager();
        NodeTypeTemplate ntt = manager.createNodeTypeTemplate();
        ntt.setName("nt:olapdatasource");

        String[] str = new String[]{"nt:file"};
        ntt.setDeclaredSuperTypeNames(str);
        ntt.setMixin(true);

        PropertyDefinitionTemplate pdt3 = manager.createPropertyDefinitionTemplate();

        pdt3.setName("jcr:data");
        pdt3.setRequiredType(PropertyType.STRING);

        PropertyDefinitionTemplate pdt4 = manager.createPropertyDefinitionTemplate();

        pdt4.setName("enabled");
        pdt4.setRequiredType(PropertyType.STRING);

        ntt.getPropertyDefinitionTemplates().add(pdt3);
        ntt.getPropertyDefinitionTemplates().add(pdt4);
        manager.registerNodeType(ntt, false);
    }

    public void createSchemas() throws RepositoryException {

        NodeTypeManager manager =
                session.getWorkspace().getNodeTypeManager();
        NodeTypeTemplate ntt = manager.createNodeTypeTemplate();
        ntt.setName("nt:mondrianschema");
        //ntt.setPrimaryItemName("nt:file");
        String[] str = new String[]{"nt:file"};
        ntt.setDeclaredSuperTypeNames(str);
        ntt.setMixin(true);
        PropertyDefinitionTemplate pdt = manager.createPropertyDefinitionTemplate();

        pdt.setName("schemaname");
        pdt.setRequiredType(PropertyType.STRING);
        pdt.isMultiple();
        PropertyDefinitionTemplate pdt2 = manager.createPropertyDefinitionTemplate();

        pdt2.setName("cubenames");
        pdt2.setRequiredType(PropertyType.STRING);
        pdt2.isMultiple();

        PropertyDefinitionTemplate pdt3 = manager.createPropertyDefinitionTemplate();

        pdt3.setName("jcr:data");
        pdt3.setRequiredType(PropertyType.STRING);

        ntt.getPropertyDefinitionTemplates().add(pdt);
        ntt.getPropertyDefinitionTemplates().add(pdt2);
        ntt.getPropertyDefinitionTemplates().add(pdt3);


        manager.registerNodeType(ntt, false);
    }

    public void createFiles() throws RepositoryException {

        NodeTypeManager manager =
                session.getWorkspace().getNodeTypeManager();
        NodeTypeTemplate ntt = manager.createNodeTypeTemplate();
        ntt.setName("nt:saikufiles");
        String[] str = new String[]{"nt:file"};
        ntt.setDeclaredSuperTypeNames(str);
        ntt.setMixin(true);
        PropertyDefinitionTemplate pdt = manager.createPropertyDefinitionTemplate();
        pdt.setName("owner");
        pdt.setRequiredType(PropertyType.STRING);


        PropertyDefinitionTemplate pdt2 = manager.createPropertyDefinitionTemplate();
        pdt2.setName("type");
        pdt2.setRequiredType(PropertyType.STRING);

        PropertyDefinitionTemplate pdt4 = manager.createPropertyDefinitionTemplate();
        pdt4.setName("roles");
        pdt4.setRequiredType(PropertyType.STRING);

        PropertyDefinitionTemplate pdt5 = manager.createPropertyDefinitionTemplate();
        pdt5.setName("users");
        pdt5.setRequiredType(PropertyType.STRING);


        PropertyDefinitionTemplate pdt3 = manager.createPropertyDefinitionTemplate();
        pdt3.setName("jcr:data");
        pdt3.setRequiredType(PropertyType.STRING);

        ntt.getPropertyDefinitionTemplates().add(pdt);
        ntt.getPropertyDefinitionTemplates().add(pdt2);
        ntt.getPropertyDefinitionTemplates().add(pdt3);
        ntt.getPropertyDefinitionTemplates().add(pdt4);
        ntt.getPropertyDefinitionTemplates().add(pdt5);

        manager.registerNodeType(ntt, false);
    }
}