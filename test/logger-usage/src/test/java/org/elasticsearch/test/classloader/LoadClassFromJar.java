package org.elasticsearch.test.classloader;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;

public class LoadClassFromJar {
    public static void main(String[] args) throws IOException, ClassNotFoundException {

//        String path = "H:\\音乐";
//        DirectoryStream<Path> paths = Files.newDirectoryStream(Paths.get(path));
//
//        paths.forEach(path1 -> {
//            System.out.println(path1.toUri().getPath());
//        });

        Path dir = Paths.get("G:\\qzd\\JavaProject\\QZD_GROUP\\openSource\\github\\elasticsearch-7.6.0\\home\\plugins\\vector-scoring");

        Set<URL> urls = new LinkedHashSet<>();
        // gather urls for jar files
        try (DirectoryStream<Path> jarStream = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path jar : jarStream) {
                // normalize with toRealPath to get symlinks out of our hair
                URL url = jar.toRealPath().toUri().toURL();
                if (urls.add(url) == false) {
                    throw new IllegalStateException("duplicate codebase: " + url);
                }
            }
        }

        ClassLoader parentLoader = LoadClassFromJar.class.getClassLoader();
        ClassLoader loader = URLClassLoader.newInstance(urls.toArray(new URL[0]), parentLoader);

        String classname= "com.github.jfasttext.JFastText";

        Class<?> aClass = loader.loadClass(classname);

        System.out.println(aClass.getName());
    }
}
