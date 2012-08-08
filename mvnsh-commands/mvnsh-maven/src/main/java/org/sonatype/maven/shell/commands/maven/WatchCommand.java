/*
 * Copyright (c) 2012 Jesse Glick.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package org.sonatype.maven.shell.commands.maven;

import com.google.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement;
import org.sonatype.gshell.command.Command;
import org.sonatype.gshell.command.CommandContext;
import org.sonatype.gshell.command.support.CommandActionSupport;
import org.sonatype.gshell.util.cli2.CliProcessor;
import org.sonatype.gshell.util.cli2.CliProcessorAware;
import org.sonatype.gshell.util.cli2.Option;
import org.sonatype.gshell.util.pref.Preferences;
import org.sonatype.maven.shell.maven.MavenSystem;

// XXX create WatchCommand.{help,properties}

/**
 * Watch a directory for source changes and try to run necessary Maven builds.
 */
@Command(name="watch")
@Preferences(path="commands/watch")
public class WatchCommand extends CommandActionSupport implements CliProcessorAware {

    private final MavenSystem maven;

    @Option(name="d", longName="directory")
    private File directory;

    @Inject
    public WatchCommand(MavenSystem maven) {
        this.maven = maven;
    }

    @Override public void setProcessor(CliProcessor processor) {
        processor.setFlavor(CliProcessor.Flavor.GNU);
    }

    @IgnoreJRERequirement
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override public Object execute(final CommandContext context) throws Exception {
        try {
            Class.forName("java.nio.file.Path");
        } catch (ClassNotFoundException x) {
            throw new UnsupportedOperationException("The watch command is only available on JDK 7+.");
        }
        final PrintWriter err = context.getIo().err;
        final WatchService svc = FileSystems.getDefault().newWatchService();
        final Set<? super WatchKey> keys = new HashSet<WatchKey>(); // just to avoid GC?
        class Visitor extends SimpleFileVisitor/*<Path>*/ {
            @IgnoreJRERequirement // MANIMALSNIFFER-29: annotation on execute(...) does not suffice
            Visitor() {}
            @IgnoreJRERequirement
            @Override public FileVisitResult preVisitDirectory(Object/*Path*/ _dir, BasicFileAttributes attrs) throws IOException {
                Path dir = (Path) _dir;
                err.println("XXX watching " + dir);
                if (dir.endsWith("target")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                keys.add(dir.register(svc, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY));
                return super.preVisitDirectory(dir, attrs);
            }
        }
        err.println("XXX execute on " + directory);
        Files.walkFileTree(directory.toPath(), EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, new Visitor());
        Thread t = new Thread("WatchCommand") {
            @IgnoreJRERequirement
            @Override public void run() {
                while (true) {
                    err.println("XXX listening...");
                    try {
                        WatchKey key = svc.take();
                        err.println("XXX got " + key.watchable());
                        for (WatchEvent<?> ev : key.pollEvents()) {
                            Path p = ((Path) key.watchable()).resolve((Path) ev.context());
                            // XXX seem to get duplicate events
                            err.println("XXX " + p + " got " + ev.kind());
                            boolean dir = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS);
                            if (ev.kind() == StandardWatchEventKinds.ENTRY_CREATE && dir) {
                                try {
                                    Files.walkFileTree(p, new Visitor());
                                } catch (IOException ex) {
                                    Logger.getLogger(WatchCommand.class.getName()).log(Level.SEVERE, null, ex);
                                }
                            }
                            File d = dir ? p.toFile() : p.getParent().toFile();
                            while (d != null) {
                                if (new File(d, "pom.xml").isFile()) {
                                    break;
                                }
                                d = d.getParentFile();
                            }
                            if (d == null) {
                                err.println("XXX found no Maven module");
                            } else {
                                err.println("XXX -> build " + d);
                                // XXX need to set name
                                // XXX optionally, find root reactor and use -amd -pl to build downstream projects
                                // XXX repeated runs seem to fail?
                                MavenCommand cmd = new MavenCommand(maven);
                                cmd.file = new File(d, "pom.xml");
                                cmd.goals = Arrays.asList("package");
                                try {
                                    cmd.execute(context);
                                } catch (Exception ex) {
                                    Logger.getLogger(WatchCommand.class.getName()).log(Level.SEVERE, null, ex);
                                }
                            }
                        }
                        if (!key.reset()) {
                            keys.remove(key);
                        }
                        if (keys.isEmpty()) {
                            break;
                        }
                    } catch (InterruptedException ex) {
                        Logger.getLogger(WatchCommand.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
            }
        };
        t.setDaemon(true);
        t.start();
        return 0;
    }

}
