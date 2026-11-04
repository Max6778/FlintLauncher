package com.flintlauncher.ui.subassembly.filelist;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Environment;
import android.util.AttributeSet;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;

import com.flintlauncher.R;
import com.flintlauncher.task.TaskExecutors;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@SuppressLint("ViewConstructor")
public class FileRecyclerView extends LinearLayout {
    private final AtomicInteger listingRequest = new AtomicInteger(0);
    private Context context;
    private FileRecyclerViewCreator fileRecyclerViewCreator;
    private FileIcon fileIcon = FileIcon.FILE;
    private SetTitleListener mSetTitleListener;
    private FileSelectedListener fileSelectedListener;
    private RefreshListener mRefreshListener;
    private File fullPath;
    private File lockPath = new File("/");
    private boolean showFiles = true;
    private boolean showFolders = true;
    private String filterString = "";
    private boolean showSearchResultsOnly = false;
    private boolean caseSensitive = false;

    public FileRecyclerView(Context context) {
        this(context, null);
    }

    public FileRecyclerView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FileRecyclerView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context);
    }

    public void init(final Context context) {
        this.context = context;

        LayoutParams layParam = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        setOrientation(VERTICAL);

        RecyclerView mainLv = new RecyclerView(context);

        fileRecyclerViewCreator = new FileRecyclerViewCreator(
                context,
                mainLv,
                (position, fileItemBean) -> {
                    if (position == 0 && !lockPath.equals(fullPath)) {
                        parentDir();
                    } else {
                        listFileAt(fileItemBean.file);
                    }
                },
                (position, fileItemBean) -> {
                    File file = fileItemBean.file;
                    if (file != null) {
                        if (position == 0 && !lockPath.equals(fullPath)) {
                            parentDir();
                        } else {
                            fileSelectedListener.onItemLongClick(file, file.getAbsolutePath());
                        }
                    }
                },
                new ArrayList<>());

        addView(mainLv, layParam);
    }

    public void setFileSelectedListener(FileSelectedListener listener) {
        this.fileSelectedListener = listener;
    }

    public void setOnMultiSelectListener(FileRecyclerAdapter.OnMultiSelectListener listener) {
        this.fileRecyclerViewCreator.setOnMultiSelectListener(listener);
    }

    public void setTitleListener(SetTitleListener setTitleListener) {
        this.mSetTitleListener = setTitleListener;
    }

    public void setRefreshListener(RefreshListener listener) {
        this.mRefreshListener = listener;
    }

    public void setShowFiles(boolean showFiles) {
        this.showFiles = showFiles;
    }

    public void setShowFolders(boolean showFolders) {
        this.showFolders = showFolders;
    }

    public void setFileIcon(FileIcon fileIcon) {
        this.fileIcon = fileIcon;
    }

    public boolean isNoFile() {
        return fileRecyclerViewCreator.isNoFile();
    }

    public void searchFiles(String filterString, boolean caseSensitive, Consumer<Integer> onComplete) {
        this.filterString = filterString;
        this.caseSensitive = caseSensitive;
        loadPath(fullPath, filterString, showSearchResultsOnly, caseSensitive, new AtomicInteger(0), onComplete);
    }

    public void setShowSearchResultsOnly(boolean showSearchResultsOnly) {
        this.showSearchResultsOnly = showSearchResultsOnly;
    }

    public FileRecyclerAdapter getAdapter() {
        return fileRecyclerViewCreator.fileRecyclerAdapter;
    }

    public int getItemCount() {
        return fileRecyclerViewCreator.fileRecyclerAdapter.getItemCount();
    }

    public void lockAndListAt(File lockPath, File listPath) {
        this.lockPath = lockPath;
        listFileAt(listPath);
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    public void listFileAt(final File path) {
        if (path == null || !path.exists()) {
            listFileAt(Environment.getExternalStorageDirectory());
            Toast.makeText(context, R.string.file_does_not_exist, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!path.isDirectory()) {
            if (fileSelectedListener != null) fileSelectedListener.onFileSelected(path, path.getAbsolutePath());
            return;
        }

        fullPath = path;
        if (mSetTitleListener != null) mSetTitleListener.setTitle(path.getAbsolutePath());
        loadPath(path, filterString, showSearchResultsOnly, caseSensitive, new AtomicInteger(0), null);
        filterString = "";
    }

    private void loadPath(final File path, final String filter, final boolean resultsOnly,
            final boolean caseSensitive, final AtomicInteger resultCount, final Consumer<Integer> onComplete) {
        if (path == null) return;
        final int request = listingRequest.incrementAndGet();
        final FileIcon icon = fileIcon;
        final boolean includeFiles = showFiles;
        final boolean includeFolders = showFolders;
        final File currentLockPath = lockPath;
        TaskExecutors.getDefault().execute(() -> {
            List<FileItemBean> itemBeans = FileRecyclerViewCreator.loadItemBeansFromPath(
                    context, filter, resultsOnly, caseSensitive, resultCount, path,
                    icon, includeFiles, includeFolders);
            Collections.sort(itemBeans);

            if (!path.equals(currentLockPath)) {
                FileItemBean parent = new FileItemBean(
                        "..", context.getResources().getDrawable(R.drawable.ic_folder, context.getTheme()));
                parent.isCanCheck = false;
                itemBeans.add(0, parent);
            }

            TaskExecutors.runInUIThread(() -> {
                if (request != listingRequest.get()) return;
                fileRecyclerViewCreator.loadData(itemBeans);
                if (mRefreshListener != null) mRefreshListener.onRefresh();
                if (onComplete != null) onComplete.accept(resultCount.get());
            });
        });
    }

    public File getFullPath() {
        return fullPath;
    }

    public void refreshPath() {
        listFileAt(getFullPath());
    }

    @Override
    protected void onDetachedFromWindow() {
        listingRequest.incrementAndGet();
        super.onDetachedFromWindow();
    }

    public void parentDir() {
        if (!fullPath.getAbsolutePath().equals("/")) {
            listFileAt(fullPath.getParentFile());
        }
    }
}
