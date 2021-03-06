package webtv;

import java.awt.Image;
import java.awt.Point;
import java.awt.PopupMenu;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.*;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.*;
import webtv.lnk.LNKList;
import webtv.tv3play.Program;
import webtv.tv3play.TV3Play;
import webtv.tv3webtv.SiteMapNode;
import webtv.zebra.ZebraList;

/**
 *
 * @author marius
 */
public class Main extends JFrame implements TreeWillExpandListener, 
                                            ActionListener, DownloadListener
{
    static final int MAX_DOWNLOADS = 1;
    JTree tree;
    DefaultMutableTreeNode root;
    FileLinkList files;
    DefaultTreeModel model;
    JPopupMenu nodeMenu;
    JPopupMenu prodMenu;
    Queue<Product> queue = new ConcurrentLinkedQueue<>();
    HashSet<Product> active = new HashSet<>();
    static final String pngs[] = {"tv3-16.png","tv3-24.png","tv3-32.png","tv3-48.png","tv3-64.png" };
    private static final String refreshMenu = "Refresh";
    private static final String launchMenu = "Launch";    
    private static final String enqueueMenu = "Enqueue";
    private static final String downloadMenu = "Download";
    private static final String deleteMenu = "Delete";    
    private static final String playMenu = "Play";

    public Main(){
        model = new DefaultTreeModel(null);
        root = new DefaultMutableTreeNode("WebTV ;-)");
        model.setRoot(root);
        root.add(new TV3Play(model));
        root.add(new LNKList(model));        
        root.add(new ZebraList(model));
        root.add(new SiteMapNode(model, "TV3 WebTV.lt", "0"));
        root.add(files = new FileLinkList(model));        
        tree = new JTree(model);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setEditable(false);
        tree.addTreeWillExpandListener(Main.this);
        tree.addMouseListener(new PopupListener());
        //tree.getModel().valueForPathChanged(arg0, tree)
        //tree.setShowsRootHandles(true);
        tree.addKeyListener(new KeyListener(){
            @Override
            public void keyTyped(KeyEvent e) {
                TreePath path = tree.getSelectionPath();
                if (path == null) return;                
                Object o = path.getLastPathComponent();
                switch (e.getKeyChar()) {
                    case 10:
                    case 13:
                        execute(path);
                        break;
                    case KeyEvent.VK_PROPS:
                        Point point = tree.getMousePosition();
                        if (o instanceof Product) {
                            prodMenu.show(tree, point.x, point.y);
                        } else {
                            nodeMenu.show(tree, point.x, point.y);
                        }
                        break;
                    case 127:
                        if (o instanceof Product) {
                            Product p = (Product) o;
                            p.delete();
                        } else if (o instanceof WGetNode){
                            WGetNode w = (WGetNode) o;
                            w.delete();
                        }
                        break;
                    default:
                        System.out.println("Typed: "+((int)e.getKeyChar())+" "+e);
                }
            }
            @Override
            public void keyPressed(KeyEvent e) {}
            @Override
            public void keyReleased(KeyEvent e) {}
        });
        nodeMenu = createNodeMenu();
        prodMenu = createProductMenu();
        add(new JScrollPane(tree));
        ArrayList<Image> imageList = new ArrayList<>(pngs.length);
        for (String name: pngs) {
            URL url = getClass().getResource("/webtv/"+name);
            if (url == null) continue;
            imageList.add(new ImageIcon(url).getImage());
        }
        if (!imageList.isEmpty()) setIconImages(imageList);

        setTitle("WebTV :-)");
        setSize(800, 750);
        setVisible(true);
//        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                try {
                    for (Product p : queue) {
                        p.setScheduled(false);
                    }
                    queue.clear();
                    Iterator<Product> i = active.iterator();
                    while (i.hasNext()) {
                        Product p = i.next();
                        p.cancelDownload();
                    }
                    active.clear();
                } catch (Exception ex) {
                    ex.printStackTrace(System.err);
                }
                System.exit(0);
            }
        });
    }

    @Override
    public synchronized void finished(Product p) {
        boolean c = active.remove(p);
        if (!c) {
            System.err.println("Completed download ("+p+") but it was not among active!");
        }
        p.removeDownloadListener(this);
        if (active.size()<MAX_DOWNLOADS) {            
            if (!queue.isEmpty()) {
                //System.out.println("Starting new download");
                p = queue.poll();
                p.addDownloadListener(this);
                p.download();
                active.add(p);
            } else {
                //System.out.println("Empty queue");
            }
        }
    }

    class PopupListener extends MouseAdapter {
        @Override
        public void mousePressed(MouseEvent e) {
            showPopup(e);
        }
        @Override
        public void mouseReleased(MouseEvent e) {
            if (!showPopup(e)) {
                if (e.getButton() == MouseEvent.BUTTON1) {
                    int selRow = tree.getRowForLocation(e.getX(), e.getY());
                    TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                    //System.out.println("Mouse released at row="+selRow);
                    if (selRow != -1) {
                        if (e.getClickCount() == 2) {
                            //System.out.println("DoubleClick");
                            execute(path);
                        }
                    }
                } else if (e.getButton() == MouseEvent.BUTTON2) {
                    try {
                        String sel = (String) Toolkit.getDefaultToolkit().getSystemSelection().getData(DataFlavor.stringFlavor);
                        if (sel == null) {
                            sel = (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
                        }
                        URI url = new URI(sel);
                        String scheme = url.getScheme();
                        if ("http".equals(scheme) || "https".equals(scheme)) {
                            if (url.getHost().contains("tv3play.lt")) {
                                try {
                                    String filename = url.toURL().getFile();
                                    if (filename.indexOf('?') >= 0) {
                                        filename = filename.substring(0, filename.indexOf('?'));
                                    }
                                    if (filename.endsWith("/")) {
                                        filename = filename.substring(0, filename.length() - 1);
                                    }
                                    String[] path = filename.split("/");
                                    String id = path[path.length - 1];
                                    files.add(new Program(model, id));
                                } catch (MalformedURLException ex) {
                                    Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
                                }
                            } else {
                                files.add(new WGetNode(model, url));
                            }
                            tree.expandRow(1);
                            files.repaintStructure();
                        }
                    } catch (URISyntaxException ex) {
                        Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
                    } catch (java.net.MalformedURLException ex) {
                        Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                    } catch (UnsupportedFlavorException | IOException ex) {
                        Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
            }
        }
        private boolean showPopup(MouseEvent e) {
            if (e.isPopupTrigger()) {
                TreePath path = tree.getClosestPathForLocation(e.getX(), e.getY());
                tree.setSelectionPath(path);
                Object o = path.getLastPathComponent();
                if (o instanceof Product || o instanceof WGetNode) {
                    prodMenu.show(e.getComponent(), e.getX(), e.getY());
                } else if (o instanceof SiteNode || o instanceof FileLinkList) {
                    nodeMenu.show(e.getComponent(), e.getX(), e.getY());
                }
                return true;
            } else return false;
        }
    }

    public void execute(TreePath path) {
        Object o = path.getLastPathComponent();
        if (o instanceof Product) {
            Product p = (Product) o;
            Product.State s = p.getState();
            switch (s) {
                default:
                case Unknown:
                case Incomplete:
                case Deleted:
                    enqueue(p);
                    break;
                case Downloading:
                case Ready:
                case Exists:
                    p.play();
                    break;
                case Scheduled:
                    if (active.size() < MAX_DOWNLOADS) {
                        download(p);
                    }
                    break;
                case Loading:
                    break;
            }
        } else if (o instanceof SiteNode){
            tree.expandPath(path);
        } else if (o instanceof FileLinkList){
            ((FileLinkList)o).refresh();
        } else if (o instanceof WGetNode) {
            WGetNode w = (WGetNode) o;
            if (w.isReady()) w.play();
            else if (!w.isBusy()) w.download();
        }
    }

    MouseListener ml = new MouseAdapter() {
        @Override
        public void mouseReleased(MouseEvent e) {            
        }
    };

    final JPopupMenu createNodeMenu(){
        JPopupMenu menu = new JPopupMenu();
        menu.add(refreshMenu).addActionListener(this);
        menu.addSeparator();
        menu.add(Settings.getMenu(this));
        return menu;
    }

    final JPopupMenu createProductMenu(){
        JPopupMenu menu = new JPopupMenu();
        menu.add(launchMenu).addActionListener(this);
        menu.add(enqueueMenu).addActionListener(this);
        menu.add(downloadMenu).addActionListener(this);
        menu.add(playMenu).addActionListener(this);
        menu.add(deleteMenu).addActionListener(this);
        menu.addSeparator();
        menu.add(Settings.getMenu(this));
        return menu;
    }


    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        Main main = new Main();
    }

    @Override
    public void treeWillExpand(TreeExpansionEvent e) throws ExpandVetoException {
        //System.out.println("willexpand");
        Object o = e.getPath().getLastPathComponent();
        if (o instanceof CommonNode) {
            ((CommonNode)o).refresh();
        }
    }

    @Override
    public void treeWillCollapse(TreeExpansionEvent arg0) throws ExpandVetoException {        
    }

    protected void enqueue(Product p) {
        if (active.size()<MAX_DOWNLOADS) {
            p.addDownloadListener(this);
            p.download();
            active.add(p);
        } else {
            p.setScheduled(true);
            queue.add(p);
        }
    }

    protected void download(Product p) {
        queue.remove(p);
        p.addDownloadListener(this);
        p.download();
        active.add(p);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        TreePath path = tree.getSelectionPath();
        if (path != null) {
            String cmd = e.getActionCommand();
            Object o = path.getLastPathComponent();
            if (o instanceof Product) {
                Product p = (Product)o;
                switch (cmd) {
                    case refreshMenu:
                        p.refresh();
                        break;
                    case enqueueMenu:
                        enqueue(p);
                        break;
                    case downloadMenu:
                        if (active.size() < MAX_DOWNLOADS) {
                            download(p);
                        } else {
                            enqueue(p);
                        }
                        break;
                    case playMenu:
                        p.play();
                        break;
                    case deleteMenu:
                        p.delete();
                        break;
                }
            } else if (o instanceof SiteNode) {
                SiteNode node = (SiteNode)o;
                node.refresh();
            } else if (o instanceof WGetNode) {
                WGetNode w = (WGetNode)o;
                if (null != cmd) switch (cmd) {
                    case downloadMenu:
                        w.download();
                        break;
                    case playMenu:
                        w.play();
                        break;
                    case deleteMenu:
                        w.delete();
                        break;
                }
            }
        }
    }

}
