package org.cryptomator.presentation.service

import android.app.job.JobInfo
import android.app.job.JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.database.MergeCursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.provider.MediaStore
import android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
import android.provider.MediaStore.Images.Media.INTERNAL_CONTENT_URI
import androidx.annotation.RequiresApi
import org.cryptomator.domain.exception.FatalBackendException
import org.cryptomator.presentation.R
import org.cryptomator.presentation.util.FileUtil
import org.cryptomator.presentation.util.ResourceHelper
import org.cryptomator.util.file.MimeTypeMap_Factory
import org.cryptomator.util.file.MimeTypes
import timber.log.Timber

@RequiresApi(api = Build.VERSION_CODES.N)
class PhotoContentJob : JobService() {

	private val handler = Handler()
	private val worker: Runnable = Runnable {
		scheduleJob(applicationContext)
		jobFinished(runningParams, false)
	}

	private lateinit var runningParams: JobParameters

	override fun onStartJob(params: JobParameters): Boolean {
		Timber.tag("PhotoContentJob").i("Job started!")

		val fileUtil = FileUtil(baseContext, MimeTypes(MimeTypeMap_Factory.newInstance()))

		runningParams = params

		params.triggeredContentAuthorities?.let {
			if (params.triggeredContentUris != null) {
				val ids = getIds(params)
				if (ids != null && ids.isNotEmpty()) {
					val selection = buildSelection(ids)
					contentResolver.query(EXTERNAL_CONTENT_URI, PROJECTION, selection, null, null).use { externalCursor ->
						contentResolver.query(INTERNAL_CONTENT_URI, PROJECTION, selection, null, null).use { internalCursor ->
							MergeCursor(arrayOf(externalCursor, internalCursor)).use { cursor ->
								while (cursor.moveToNext()) {
									try {
										val dir = cursor.getString(PROJECTION_DATA)
										fileUtil.addImageToAutoUploads(dir)
										Timber.tag("PhotoContentJob").i("Added file to UploadList")
										Timber.tag("PhotoContentJob").d(String.format("Added file to UploadList %s", dir))
									} catch (e: FatalBackendException) {
										Timber.tag("PhotoContentJob").e(e, "Failed to add image to auto upload list")
									} catch (e: SecurityException) {
										Timber.tag("PhotoContentJob").e(e, "No access to storage")
									}
								}
							}
						}
					}
				} else {
					Timber.tag("PhotoContentJob").d("ids are null or 0: %s", ids)
				}
			} else {
				Timber.tag("PhotoContentJob").w("Photos rescan needed!")
				return true
			}
		} ?: Timber.tag("PhotoContentJob").w("No photos content")

		handler.post(worker)
		return false
	}

	private fun getIds(params: JobParameters): Set<String>? {
		return params.triggeredContentUris
				?.map { it.pathSegments }
				?.filter { it != null && (it.size == EXTERNAL_CONTENT_URI.pathSegments.size + 1 || it.size == INTERNAL_CONTENT_URI.pathSegments.size + 1) }
				?.mapTo(HashSet()) { it[it.size - 1] }
	}

	private fun buildSelection(ids: Set<String>): String {
		val selection = StringBuilder()
		ids.indices.forEach { i ->
			if (selection.isNotEmpty()) {
				selection.append(" OR ")
			}
			selection.append(MediaStore.Images.ImageColumns._ID)
			selection.append("='")
			selection.append(ids.elementAt(i))
			selection.append("'")
		}
		return selection.toString()
	}

	override fun onStopJob(params: JobParameters): Boolean {
		Timber.tag("PhotoContentJob").i("onStopJob called, must stop, reschedule later")
		handler.removeCallbacks(worker)
		return true
	}

	override fun onDestroy() {
		super.onDestroy()
		Timber.tag("PhotoContentJob").i("Service is destroyed")
	}

	companion object {

		private val MEDIA_URI = Uri.parse("content://" + MediaStore.AUTHORITY + "/")
		internal val PROJECTION = arrayOf(MediaStore.Images.ImageColumns._ID, MediaStore.Images.ImageColumns.DATA)

		internal const val PROJECTION_DATA = 1

		private val jobInfo: JobInfo
		private const val PHOTOS_CONTENT_JOB = 23

		init {
			val builder = JobInfo.Builder(PHOTOS_CONTENT_JOB, ComponentName(ResourceHelper.getString(R.string.app_id), PhotoContentJob::class.java.name))
			builder.addTriggerContentUri(JobInfo.TriggerContentUri(EXTERNAL_CONTENT_URI, FLAG_NOTIFY_FOR_DESCENDANTS))
			builder.addTriggerContentUri(JobInfo.TriggerContentUri(INTERNAL_CONTENT_URI, FLAG_NOTIFY_FOR_DESCENDANTS))
			builder.addTriggerContentUri(JobInfo.TriggerContentUri(MEDIA_URI, FLAG_NOTIFY_FOR_DESCENDANTS))
			jobInfo = builder.build()
		}

		fun scheduleJob(context: Context) {
			context.getSystemService(JobScheduler::class.java)?.let {
				val result = it.schedule(jobInfo)
				if (result == JobScheduler.RESULT_SUCCESS) {
					Timber.tag("PhotoContentJob").i("Job rescheduled!")
				} else {
					Timber.tag("PhotoContentJob").e("Failed to reschedule job!")
				}
			} ?: Timber.tag("PhotoContentJob").e("Service not found!")
		}

		fun cancelJob(context: Context) {
			context.getSystemService(JobScheduler::class.java)?.let {
				it.cancel(PHOTOS_CONTENT_JOB)
				Timber.tag("PhotoContentJob").i("Job canceled!")
			}
		}
	}
}
