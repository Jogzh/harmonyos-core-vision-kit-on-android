package dev.corevision.android;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

interface IWorker {
    Bundle process(in ParcelFileDescriptor input, in ParcelFileDescriptor output);
    oneway void cancel(int requestId);
}
