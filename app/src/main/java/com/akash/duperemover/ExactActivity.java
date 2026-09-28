package com.akash.duperemover;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Exact file and folder duplicates only; no visual similarity matching. */
public class ExactActivity extends Activity {
    private static final int PICK=42, PERMISSION=43, PAGE=80;
    private static final int BG=0xff101827, CARD=0xff1c2940, SOFT=0xff293954, TEXT=0xffedf3fa;
    private static final int DIM=0xffa7b8cc, BLUE=0xff5199ff, GREEN=0xff48df99, ORANGE=0xffffbc60, RED=0xffe75b67;
    private final ExecutorService work=Executors.newSingleThreadExecutor(), hashes=Executors.newFixedThreadPool(3), thumbs=Executors.newFixedThreadPool(2);
    private final LruCache<String,Bitmap> images=new LruCache<String,Bitmap>(12*1024*1024){protected int sizeOf(String k,Bitmap b){return b.getByteCount();}};
    private final List<Group> groups=new ArrayList<>();
    private final List<List<String>> duplicateFolders=new ArrayList<>();
    private final Set<String> selected=new HashSet<>();
    private Uri folder;
    private boolean busy=false, folderMode=false;
    private int type=0, shown=PAGE; // all, photos, videos, documents
    private TextView status,summary, report;
    private LinearLayout results, controls;
    private Button scan, delete;
    private ProgressBar progress;
    private int filesCount, skippedCount;

    static class Item {
        File file; Uri uri; String name,path,kind; long size,modified; boolean image; String sha,quick;
        String key(){return file!=null?file.getAbsolutePath():uri.toString();}
    }
    static class Group {
        List<Item> items;
        Group(List<Item> i){items=i;}
    }
    static class FolderNode {
        String path; Map<String,Item> files=new HashMap<>(); Map<String,FolderNode> dirs=new HashMap<>();
        boolean complete=true; String signature;
        FolderNode(String p){path=p;}
    }
    private final Map<String,FolderNode> scannedFolders=new HashMap<>();
    private void recordFolder(String path,String parent,String name){
        FolderNode node=scannedFolders.computeIfAbsent(path,FolderNode::new);
        if(parent!=null){FolderNode up=scannedFolders.computeIfAbsent(parent,FolderNode::new);up.dirs.put(name,node);}
    }
    private void incomplete(String path){FolderNode node=scannedFolders.get(path);if(node!=null)node.complete=false;}
    private String folderSignature(FolderNode node,Set<String> visiting){
        if(node.signature!=null)return node.signature;
        if(!node.complete||!visiting.add(node.path))return null;
        try{MessageDigest d=MessageDigest.getInstance("SHA-256");
            List<String> parts=new ArrayList<>();
            for(Map.Entry<String,Item> e:node.files.entrySet()){
                Item i=e.getValue();if(i.sha==null)return null;
                parts.add("F:"+e.getKey().length()+":"+e.getKey()+":"+i.size+":"+i.sha);
            }
            for(Map.Entry<String,FolderNode> e:node.dirs.entrySet()){
                String sig=folderSignature(e.getValue(),visiting);if(sig==null)return null;
                parts.add("D:"+e.getKey().length()+":"+e.getKey()+":"+sig);
            }
            Collections.sort(parts);for(String part:parts)d.update(part.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            node.signature=hex(d.digest());return node.signature;
        }catch(Exception ex){return null;}finally{visiting.remove(node.path);}
    }
    private List<List<String>> matchFolders(){
        Map<String,List<String>> bySignature=new HashMap<>();
        for(FolderNode node:scannedFolders.values()){
            String sig=folderSignature(node,new HashSet<>());
            if(sig!=null)bySignature.computeIfAbsent(sig,k->new ArrayList<>()).add(node.path);
        }
        List<List<String>> matched=new ArrayList<>();for(List<String> paths:bySignature.values())if(paths.size()>1){
            Collections.sort(paths);matched.add(paths);
        }
        matched.sort((a,b)->a.get(0).compareToIgnoreCase(b.get(0)));return matched;
    }
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable bg(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(14));return d;}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;}
    private Button button(String s,int color){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(TEXT);b.setTextSize(13);b.setBackground(bg(color));b.setPadding(dp(12),dp(8),dp(12),dp(8));return b;}
    private void add(LinearLayout parent,View child){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(5),0,dp(5));parent.addView(child,lp);}
    private void heading(LinearLayout parent,String s,int color){TextView t=text(s,15,color);t.setTypeface(null,Typeface.BOLD);add(parent,t);}
    @Override public void onCreate(Bundle saved){super.onCreate(saved);
        LinearLayout page=new LinearLayout(this);page.setOrientation(1);page.setPadding(dp(14),dp(14),dp(14),dp(8));page.setBackgroundColor(BG);
        heading(page,"Duplicate File Remover",TEXT);
        add(page,text("Only byte-identical files and folders. Folder matches are review-only; file deletion is permanent.",12,DIM));
        status=text("Choose Full phone or a folder, then a file type.",13,DIM);add(page,status);
        LinearLayout modes=new LinearLayout(this);modes.setOrientation(0);
        Button full=button("Full phone",BLUE);full.setOnClickListener(v->{folderMode=false;permissionThenScan();});modes.addView(full,new LinearLayout.LayoutParams(0,dp(48),1));
        Button choose=button("Choose folder",SOFT);choose.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,PICK);});
        LinearLayout.LayoutParams half=new LinearLayout.LayoutParams(0,dp(48),1);half.leftMargin=dp(8);modes.addView(choose,half);add(page,modes);
        controls=new LinearLayout(this);controls.setOrientation(0);String[] names={"All files","Photos","Videos","Documents"};
        for(int i=0;i<4;i++){final int n=i;Button b=button(names[i],SOFT);b.setOnClickListener(v->{type=n;for(int j=0;j<controls.getChildCount();j++)controls.getChildAt(j).setBackground(bg(j==n?BLUE:SOFT));status.setText("Filter: "+names[n]+". Tap Scan or Full phone.");});
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(44),1);p.rightMargin=dp(3);controls.addView(b,p);}
        controls.getChildAt(0).setBackground(bg(BLUE));add(page,controls);
        scan=button("Scan again",BLUE);scan.setOnClickListener(v->permissionThenScan());add(page,scan);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);progress.setVisibility(View.GONE);add(page,progress);
        summary=text("",13,TEXT);add(page,summary);
        report=text("",12,DIM);add(page,report);

        ScrollView scroll=new ScrollView(this);results=new LinearLayout(this);results.setOrientation(1);scroll.addView(results);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout actions=new LinearLayout(this);
        Button all=button("Select all exact",SOFT);all.setOnClickListener(v->{for(Group g:groups)for(int i=1;i<g.items.size();i++)selected.add(g.items.get(i).key());render();});actions.addView(all,new LinearLayout.LayoutParams(0,dp(50),1));
        Button clear=button("Clear",SOFT);clear.setOnClickListener(v->{selected.clear();render();});actions.addView(clear,new LinearLayout.LayoutParams(0,dp(50),.5f));
        delete=button("Delete selected",RED);delete.setOnClickListener(v->confirmDelete());actions.addView(delete,new LinearLayout.LayoutParams(0,dp(50),1));add(page,actions);
        setContentView(page);
    }
    private void busy(boolean b){busy=b;progress.setVisibility(b?View.VISIBLE:View.GONE);scan.setEnabled(!b);delete.setEnabled(!b&&!selected.isEmpty());}
    private void say(String s){runOnUiThread(()->status.setText(s));}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(request!=PICK||result!=RESULT_OK||data==null||data.getData()==null)return;
        try{folder=data.getData();int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);getContentResolver().takePersistableUriPermission(folder,flags);folderMode=true;startScan();}
        catch(Exception ex){status.setText("Folder access failed: "+ex.getMessage());}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==PERMISSION){if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)startScan();else status.setText("Storage permission denied. Choose folder instead.");}}
    @Override protected void onResume(){super.onResume();if(waitingForAccess&&Build.VERSION.SDK_INT>=30&&Environment.isExternalStorageManager()){waitingForAccess=false;startScan();}}
    private boolean waitingForAccess=false;
    private void permissionThenScan(){if(busy)return;if(folderMode){if(folder==null){status.setText("Choose a folder first.");return;}startScan();return;}
        if(Build.VERSION.SDK_INT>=30&&!Environment.isExternalStorageManager()){
            waitingForAccess=true;new AlertDialog.Builder(this).setTitle("Full phone access").setMessage("Allow 'All files access' in Android settings. Only shared storage can be scanned, not other apps' private folders or restricted Android/data. If an SD card is blocked, choose it with Choose folder.").setNegativeButton("Cancel",(d,w)->waitingForAccess=false).setPositiveButton("Open settings",(d,w)->{
                try{startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:"+getPackageName())));}catch(Exception ex){startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));}}).show();return;}
        if(Build.VERSION.SDK_INT<30&&checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.WRITE_EXTERNAL_STORAGE},PERMISSION);return;}
        startScan();
    }
    private void startScan(){if(busy)return;groups.clear();duplicateFolders.clear();scannedFolders.clear();selected.clear();results.removeAllViews();summary.setText("");report.setText("");busy(true);say("Finding accessible files...");
        final boolean useFolder=folderMode;final Uri tree=folder;final int fileType=type;
        work.execute(()->{List<Item> list=new ArrayList<>();filesCount=0;skippedCount=0;
            try{
                if(useFolder){if(tree==null)throw new Exception("No folder selected");walkTree(tree,list,fileType);}
                else {List<File> roots=new ArrayList<>();roots.add(Environment.getExternalStorageDirectory());
                    if(Build.VERSION.SDK_INT>=30){StorageManager sm=(StorageManager)getSystemService(STORAGE_SERVICE);for(StorageVolume vol:sm.getStorageVolumes())if(vol.getDirectory()!=null&&!roots.contains(vol.getDirectory()))roots.add(vol.getDirectory());}
                    else for(File f:getExternalFilesDirs(null))if(f!=null){String p=f.getAbsolutePath();int cut=p.indexOf("/Android/data/");if(cut>0){File root=new File(p.substring(0,cut));if(!roots.contains(root))roots.add(root);}}
                    for(File root:roots)walkFiles(root,list,fileType);
                }
                filesCount=list.size();say("Checking "+list.size()+" files by size and hash...");
                Map<Long,List<Item>> sizes=new HashMap<>();for(Item i:list)sizes.computeIfAbsent(i.size,k->new ArrayList<>()).add(i);
                List<Item> candidates=new ArrayList<>();for(List<Item> v:sizes.values())if(v.size()>1)candidates.addAll(v);
                parallelHash(candidates,true);
                Map<String,List<Item>> quicks=new HashMap<>();for(Item i:candidates)if(i.quick!=null)quicks.computeIfAbsent(i.size+":"+i.quick,k->new ArrayList<>()).add(i);
                List<Item> finalists=new ArrayList<>();for(List<Item> v:quicks.values())if(v.size()>1)finalists.addAll(v);
                parallelHash(finalists,false);
                Map<String,List<Item>> exacts=new HashMap<>();for(Item i:finalists)if(i.sha!=null)exacts.computeIfAbsent(i.size+":"+i.sha,k->new ArrayList<>()).add(i);
                List<Group> found=new ArrayList<>();
                for(List<Item> v:exacts.values())if(v.size()>1){Collections.sort(v,(a,b)->a.path.compareToIgnoreCase(b.path));found.add(new Group(v));}
                found.sort((a,b)->Long.compare(b.items.get(0).size*(b.items.size()-1L),a.items.get(0).size*(a.items.size()-1L)));
                List<List<String>> foldersFound=new ArrayList<>();
                if(fileType==0){say("Verifying folder contents by SHA-256...");
                    List<Item> rest=new ArrayList<>();for(Item i:list)if(i.sha==null)rest.add(i);
                    parallelHash(rest,false);foldersFound=matchFolders();
                }
                final List<List<String>> matches=foldersFound;
                runOnUiThread(()->{groups.addAll(found);duplicateFolders.addAll(matches);busy(false);render();status.setText("Scan complete: "+filesCount+" accessible "+kindName(fileType)+" files; "+skippedCount+" unreadable/skipped."+(fileType!=0?" Select All files to verify whole folders.":""));});
            }catch(Exception ex){runOnUiThread(()->{busy(false);status.setText("Scan stopped: "+ex.getMessage());});}
        });
    }
    private String kindName(int k){return new String[]{"all-type","photo","video","document"}[k];}
    private boolean wanted(String name,int kind){String s=name.toLowerCase(Locale.ROOT);if(kind==0)return true;
        if(kind==1)return s.matches(".*\\.(jpg|jpeg|png|webp|gif|bmp|heic|heif|avif|tif|tiff)$");
        if(kind==2)return s.matches(".*\\.(mp4|mkv|mov|avi|3gp|webm|m4v|wmv)$");
        return s.matches(".*\\.(pdf|doc|docx|txt|rtf|odt|xls|xlsx|csv|ods|ppt|pptx|odp|epub)$");}
    private String kind(String name){if(wanted(name,1))return "Photos";if(wanted(name,2))return "Videos";if(wanted(name,3))return "Documents";return "Other files";}
    private void walkFiles(File root,List<Item> out,int kind){if(root==null||!root.exists())return;ArrayDeque<File> stack=new ArrayDeque<>();stack.push(root);Set<String> seen=new HashSet<>();
        if(kind==0)recordFolder(root.getAbsolutePath(),null,root.getName());
        while(!stack.isEmpty()){File d=stack.pop();String dir=d.getAbsolutePath();try{String canonical=d.getCanonicalPath();if(!seen.add(canonical)||!canonical.equals(dir)){incomplete(dir);continue;}}catch(Exception e){skippedCount++;incomplete(dir);continue;}
            if(dir.contains("/Android/data")||dir.contains("/Android/obb")){incomplete(dir);continue;}
            File[] children=d.listFiles();if(children==null){skippedCount++;incomplete(dir);continue;}for(File f:children){if(f.getName().startsWith(".")){incomplete(dir);continue;}
                if(f.isDirectory()){if(kind==0)recordFolder(f.getAbsolutePath(),dir,f.getName());stack.push(f);continue;}
                if(!f.isFile()||!f.canRead()){skippedCount++;incomplete(dir);continue;}if(!wanted(f.getName(),kind))continue;
                Item i=new Item();i.file=f;i.name=f.getName();i.path=f.getAbsolutePath();i.size=f.length();i.modified=f.lastModified();i.kind=kind(i.name);i.image="Photos".equals(i.kind);out.add(i);
                if(kind==0)scannedFolders.get(dir).files.put(i.name,i);
                if(out.size()%1000==0)say("Found "+out.size()+" files...");}}
    }
    private void walkTree(Uri tree,List<Item> out,int kind)throws Exception{String root=DocumentsContract.getTreeDocumentId(tree);ArrayDeque<String> pending=new ArrayDeque<>();pending.push(root);Set<String> seen=new HashSet<>();
        if(kind==0)recordFolder(root,null,root);
        while(!pending.isEmpty()){String id=pending.pop();if(!seen.add(id)){incomplete(id);continue;}Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,id);
            String[] cols={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED};
            try(Cursor c=getContentResolver().query(children,cols,null,null,null)){if(c==null){skippedCount++;incomplete(id);continue;}while(c.moveToNext()){
                String child=c.getString(0),name=c.getString(1),mime=c.getString(2);if(name==null||name.startsWith(".")){incomplete(id);continue;}
                if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)){if(kind==0)recordFolder(child,id,name);pending.push(child);continue;}
                if(c.isNull(3)){skippedCount++;incomplete(id);continue;}if(!wanted(name,kind))continue;
                Item i=new Item();i.uri=DocumentsContract.buildDocumentUriUsingTree(tree,child);i.name=name;i.path=child;i.size=c.getLong(3);i.modified=c.isNull(4)?0:c.getLong(4);i.kind=kind(name);i.image="Photos".equals(i.kind);out.add(i);
                if(kind==0)scannedFolders.get(id).files.put(name,i);
                if(out.size()%1000==0)say("Found "+out.size()+" files...");}}
            catch(Exception ex){skippedCount++;incomplete(id);}}
    }
    private InputStream open(Item i)throws Exception{return i.file!=null?new FileInputStream(i.file):getContentResolver().openInputStream(i.uri);}
    private String hex(byte[] bytes){StringBuilder b=new StringBuilder();for(byte x:bytes)b.append(String.format(Locale.ROOT,"%02x",x&255));return b.toString();}
    private String quick(Item i)throws Exception{MessageDigest h=MessageDigest.getInstance("SHA-256");int chunk=65536;
        if(i.file!=null){try(RandomAccessFile r=new RandomAccessFile(i.file,"r")){byte[] b=new byte[chunk];int n=r.read(b);if(n>0)h.update(b,0,n);}} // First chunk only: same fingerprint on File and SAF providers.
        else {try(InputStream in=open(i)){if(in==null)throw new Exception("Unreadable");byte[] b=new byte[chunk];int n=in.read(b);if(n>0)h.update(b,0,n);
            // SAF providers generally do not support seeking. Full hash below is the authoritative check.
        }}return hex(h.digest());}
    private String sha(Item i)throws Exception{MessageDigest h=MessageDigest.getInstance("SHA-256");long count=0;try(InputStream in=open(i)){if(in==null)throw new Exception("Unreadable");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){h.update(b,0,n);count+=n;}}if(count!=i.size)throw new Exception("File size changed during hash");return hex(h.digest());}
    private void parallelHash(List<Item> entries,boolean fast)throws Exception{List<Future<?>> tasks=new ArrayList<>();for(Item i:entries)tasks.add(hashes.submit(()->{try{if(fast)i.quick=quick(i);else i.sha=sha(i);}catch(Exception e){synchronized(this){skippedCount++;}}}));
        int n=0;for(Future<?> f:tasks){f.get();if(++n%300==0)say((fast?"Quick checking ":"Verifying SHA-256 ")+n+"/"+tasks.size());}}
    private Bitmap decode(Item i,int max){try{BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;try(InputStream in=open(i)){BitmapFactory.decodeStream(in,null,options);}if(options.outWidth<1||options.outHeight<1)return null;
        options.inJustDecodeBounds=false;options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>max*2)options.inSampleSize*=2;
        try(InputStream in=open(i)){return BitmapFactory.decodeStream(in,null,options);}}catch(Exception e){return null;}}
    private void render(){results.removeAllViews();Map<String,Integer> folders=new LinkedHashMap<>(),types=new LinkedHashMap<>();long reclaim=0;
        for(Group g:groups)for(int j=1;j<g.items.size();j++){Item i=g.items.get(j);String dir=i.file!=null?i.file.getParent():i.path.substring(0,Math.max(0,i.path.lastIndexOf('/')));folders.put(dir,folders.getOrDefault(dir,0)+1);types.put(i.kind,types.getOrDefault(i.kind,0)+1);reclaim+=i.size;}
        summary.setText(groups.size()+" exact file groups | "+duplicateFolders.size()+" exact folder groups | "+reclaim/1048576+" MB in extra file copies");StringBuilder b=new StringBuilder("Exact extra file copies by type: ");for(Map.Entry<String,Integer> e:types.entrySet())b.append(e.getKey()).append(": ").append(e.getValue()).append("   ");b.append("\nBy folder:");
        List<Map.Entry<String,Integer>> dirs=new ArrayList<>(folders.entrySet());dirs.sort((a,c)->c.getValue()-a.getValue());for(Map.Entry<String,Integer> e:dirs)b.append("\n").append(e.getKey()).append(": ").append(e.getValue());report.setText(b.toString());
        heading(results,"EXACT FILE DUPLICATES - byte-identical, SHA-256 verified",GREEN);
        int count=0;for(Group g:groups){if(count++>=shown)continue;showGroup(g);}
        if(count>shown){Button more=button("Show next "+PAGE+" file groups",SOFT);more.setOnClickListener(v->{shown+=PAGE;render();});add(results,more);}
        heading(results,"EXACT FOLDERS - same names, structure and byte-identical files (review only)",GREEN);
        if(duplicateFolders.isEmpty())add(results,text("No verified duplicate folders in this scan.",12,DIM));
        for(List<String> paths:duplicateFolders){LinearLayout card=new LinearLayout(this);card.setOrientation(1);card.setPadding(dp(10),dp(8),dp(10),dp(8));card.setBackground(bg(CARD));
            heading(card,paths.size()+" matching folders",GREEN);
            for(int j=0;j<paths.size();j++)add(card,text(paths.get(j)+(j==0?" | KEEP":" | MATCH"),12,TEXT));add(results,card);}
        delete.setText(selected.isEmpty()?"Delete selected":"Delete ("+selected.size()+")");delete.setEnabled(!busy&&!selected.isEmpty());
    }
    private void showGroup(Group g){LinearLayout card=new LinearLayout(this);card.setOrientation(1);card.setPadding(dp(10),dp(8),dp(10),dp(8));card.setBackground(bg(CARD));
        heading(card,"EXACT COPY | "+g.items.size()+" files",GREEN);
        for(int j=0;j<g.items.size();j++){Item i=g.items.get(j);final boolean keep=j==0;LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
            if(i.image){ImageView img=new ImageView(this);img.setScaleType(ImageView.ScaleType.CENTER_CROP);row.addView(img,new LinearLayout.LayoutParams(dp(52),dp(52)));Bitmap cached=images.get(i.key());if(cached!=null)img.setImageBitmap(cached);else thumbs.execute(()->{Bitmap bitmap=decode(i,220);if(bitmap!=null){images.put(i.key(),bitmap);runOnUiThread(()->img.setImageBitmap(bitmap));}});
                img.setOnClickListener(v->{ImageView preview=new ImageView(this);preview.setAdjustViewBounds(true);new AlertDialog.Builder(this).setTitle(i.name).setMessage(i.path).setView(preview).setPositiveButton("Close",null).show();thumbs.execute(()->{Bitmap large=decode(i,1280);if(large!=null)runOnUiThread(()->preview.setImageBitmap(large));});});}
            else {TextView icon=text(i.kind.substring(0,Math.min(3,i.kind.length())),11,BLUE);icon.setGravity(Gravity.CENTER);row.addView(icon,new LinearLayout.LayoutParams(dp(52),dp(52)));}
            TextView info=text(i.name+"\n"+i.path+"\n"+(i.size/1024)+" KB"+(keep?" | KEEP":""),11,TEXT);LinearLayout.LayoutParams w=new LinearLayout.LayoutParams(0,-2,1);w.leftMargin=dp(6);row.addView(info,w);
            if(!keep){CheckBox box=new CheckBox(this);box.setChecked(selected.contains(i.key()));box.setOnCheckedChangeListener((v,checked)->{if(checked)selected.add(i.key());else selected.remove(i.key());delete.setText("Delete ("+selected.size()+")");delete.setEnabled(!busy&&!selected.isEmpty());});row.addView(box);}
            add(card,row);}
        add(results,card);
    }
    private void confirmDelete(){if(busy||selected.isEmpty())return;List<Item> chosen=new ArrayList<>();long bytes=0;
        for(Group g:groups)for(int j=1;j<g.items.size();j++)if(selected.contains(g.items.get(j).key())){Item i=g.items.get(j);chosen.add(i);bytes+=i.size;}
        String msg=chosen.size()+" files ("+bytes/1048576+" MB) will be PERMANENTLY deleted from their original folders. No app bin or undo. One KEEP file stays per group.\n\n"+
            "Changed/unreadable files will be skipped. Folders are review-only and never deleted here. Android or your file provider may still apply its own trash behavior.";
        new AlertDialog.Builder(this).setTitle("Permanently delete selected files?").setMessage(msg).setNegativeButton("Cancel",null).setPositiveButton("Delete permanently",(d,w)->deleteFiles(chosen)).show();}
    private long sizeNow(Item i)throws Exception{if(i.file!=null){if(!i.file.isFile())throw new Exception("Missing file");return i.file.length();}try(Cursor c=getContentResolver().query(i.uri,new String[]{DocumentsContract.Document.COLUMN_SIZE},null,null,null)){if(c==null||!c.moveToFirst()||c.isNull(0))throw new Exception("Size unavailable");return c.getLong(0);}}
    private void deleteFiles(List<Item> chosen){busy(true);say("Rechecking files before deletion...");List<Group> snapshot=new ArrayList<>(groups);Set<String> keys=new HashSet<>();for(Item i:chosen)keys.add(i.key());work.execute(()->{int done=0,skipped=0;
        for(Group g:snapshot){Item keep=g.items.get(0);for(int j=1;j<g.items.size();j++){Item i=g.items.get(j);if(!keys.contains(i.key()))continue;
            try{if(sizeNow(i)!=i.size||sizeNow(keep)!=keep.size){skipped++;continue;}
                String a=sha(i),b=sha(keep);if(!a.equals(i.sha)||!a.equals(b)){skipped++;continue;}
                boolean ok=i.file!=null?i.file.delete():DocumentsContract.deleteDocument(getContentResolver(),i.uri);
                if(ok)done++;else skipped++;
            }catch(Exception ex){skipped++;}}}
        final int a=done,b=skipped;runOnUiThread(()->{groups.clear();duplicateFolders.clear();scannedFolders.clear();selected.clear();images.evictAll();busy(false);render();status.setText(a+" files deleted; "+b+" skipped/failed. Scan again to refresh.");});});
    }
}
