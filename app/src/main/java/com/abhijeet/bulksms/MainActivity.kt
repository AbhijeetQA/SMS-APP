package com.abhijeet.bulksms

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.os.Bundle
import android.telephony.SmsManager
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckedTextView
import android.widget.ListView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private data class Person(val name: String, val phone: String)

    private lateinit var container: LinearLayout
    private lateinit var etTemplate: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvCount: TextView
    private lateinit var btnSend: Button
    private lateinit var btnManual: Button
    private var manualPeople: List<Person> = emptyList()
    private var manualTemplate = ""
    private var manualIdx = 0
    private var manualActive = false

    private val prefs by lazy { getSharedPreferences("bulk_sms", Context.MODE_PRIVATE) }
    private val failed = linkedSetOf<String>()
    @Volatile private var handedOver = 0
    @Volatile private var total = 0
    @Volatile private var lastError = ""

    private val sentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (resultCode != Activity.RESULT_OK) {
                lastError = "Carrier/phone result code $resultCode " + when (resultCode) {
                    SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "(generic failure: check SIM, balance, or SMS permission)"
                    SmsManager.RESULT_ERROR_NO_SERVICE -> "(no network service)"
                    SmsManager.RESULT_ERROR_RADIO_OFF -> "(airplane mode / radio off)"
                    SmsManager.RESULT_ERROR_NULL_PDU -> "(bad message)"
                    else -> ""
                }
                synchronized(failed) { failed.add(intent.getStringExtra("name") ?: "?") }
                runOnUiThread { showStatus() }
            }
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) onSendClicked()
            else Toast.makeText(this, "SMS permission is required to send.", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        container = findViewById(R.id.container)
        etTemplate = findViewById(R.id.etTemplate)
        tvStatus = findViewById(R.id.tvStatus)
        tvCount = findViewById(R.id.tvCount)
        btnSend = findViewById(R.id.btnSend)
        btnManual = findViewById(R.id.btnManual)

        ContextCompat.registerReceiver(
            this, sentReceiver, IntentFilter(ACTION_SENT), ContextCompat.RECEIVER_NOT_EXPORTED
        )

        etTemplate.setText(
            prefs.getString(
                KEY_TEMPLATE,
                "Hi {name}, thank you for visiting my home. It was good to see you. Take care. Yours, Abhijeet"
            )
        )
        loadPeople()
        if (container.childCount == 0) addRow()

        findViewById<Button>(R.id.btnAdd).setOnClickListener { addRow() }
        btnSend.setOnClickListener { onSendClicked() }
        btnManual.setOnClickListener { onManualClicked() }
        findViewById<Button>(R.id.btnContacts).setOnClickListener { onPickContactsClicked() }
    }

    override fun onPause() {
        super.onPause()
        savePrefs()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(sentReceiver)
    }

    // ---------- UI helpers ----------

    private fun addRow(name: String = "", phone: String = "") {
        if (container.childCount >= MAX_PEOPLE) {
            Toast.makeText(this, "Maximum $MAX_PEOPLE people", Toast.LENGTH_SHORT).show()
            return
        }
        val row = layoutInflater.inflate(R.layout.row_contact, container, false)
        row.findViewById<EditText>(R.id.etName).setText(name)
        row.findViewById<EditText>(R.id.etPhone).setText(phone)
        row.findViewById<View>(R.id.btnRemove).setOnClickListener {
            container.removeView(row)
            updateCount()
        }
        container.addView(row)
        updateCount()
    }

    private fun updateCount() {
        tvCount.text = "People (${container.childCount}/$MAX_PEOPLE)"
    }

    private fun showStatus() {
        val bad = synchronized(failed) { failed.toList() }
        tvStatus.text = buildString {
            append("Handed to phone: $handedOver / $total")
            if (bad.isNotEmpty()) append("\nFailed: ${bad.joinToString(", ")}")
            if (lastError.isNotEmpty()) append("\nReason: $lastError")
        }
    }

    // ---------- Storage ----------

    private fun savePrefs() {
        val arr = JSONArray()
        for (i in 0 until container.childCount) {
            val row = container.getChildAt(i)
            arr.put(JSONObject().apply {
                put("n", row.findViewById<EditText>(R.id.etName).text.toString())
                put("p", row.findViewById<EditText>(R.id.etPhone).text.toString())
            })
        }
        prefs.edit()
            .putString(KEY_TEMPLATE, etTemplate.text.toString())
            .putString(KEY_PEOPLE, arr.toString())
            .apply()
    }

    private fun loadPeople() {
        val json = prefs.getString(KEY_PEOPLE, null) ?: return
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            addRow(o.optString("n"), o.optString("p"))
        }
    }

    // ---------- Sending ----------

    private fun collectPeople(): List<Person>? {
        val list = mutableListOf<Person>()
        for (i in 0 until container.childCount) {
            val row = container.getChildAt(i)
            val name = row.findViewById<EditText>(R.id.etName).text.toString().trim()
            var phone = row.findViewById<EditText>(R.id.etPhone).text.toString()
                .filter { it.isDigit() || it == '+' }
            if (name.isEmpty() && phone.isEmpty()) continue
            if (name.isEmpty() || phone.length < 10) {
                Toast.makeText(this, "Check row ${i + 1}: name or number is missing/invalid", Toast.LENGTH_LONG).show()
                return null
            }
            if (phone.length == 10) phone = "+91$phone" // 10-digit Indian number
            list.add(Person(name, phone))
        }
        return list
    }

    private fun onSendClicked() {
        val template = etTemplate.text.toString().trim()
        if (template.isEmpty()) {
            Toast.makeText(this, "Write a message first", Toast.LENGTH_SHORT).show()
            return
        }
        val people = collectPeople() ?: return
        if (people.isEmpty()) {
            Toast.makeText(this, "Add at least one person", Toast.LENGTH_SHORT).show()
            return
        }
        savePrefs()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.SEND_SMS)
            return
        }

        val preview = template.replace("{name}", people[0].name)
        AlertDialog.Builder(this)
            .setTitle("Send to ${people.size} people?")
            .setMessage("Preview:\n\n$preview")
            .setPositiveButton("Send") { _, _ -> sendAll(template, people) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Phone book ----------

    private data class Contact(val name: String, val number: String)

    private val contactsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) showContactPicker()
            else Toast.makeText(this, "Contacts permission is needed to pick from the phone book.", Toast.LENGTH_LONG).show()
        }

    private fun onPickContactsClicked() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            return
        }
        showContactPicker()
    }

    private fun digitsKey(n: String) = n.filter { it.isDigit() }.takeLast(10)

    private fun loadContacts(): List<Contact> {
        val seen = HashSet<String>()
        val out = ArrayList<Contact>()
        val cols = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols, null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)?.trim().orEmpty()
                val num = c.getString(1)?.filter { it.isDigit() || it == '+' }.orEmpty()
                val key = digitsKey(num)
                if (name.isEmpty() || key.length < 10 || !seen.add(key)) continue
                out.add(Contact(name, num))
            }
        }
        return out
    }

    private fun showContactPicker() {
        val all = loadContacts()
        if (all.isEmpty()) {
            Toast.makeText(this, "No contacts with phone numbers found", Toast.LENGTH_LONG).show()
            return
        }
        val selected = LinkedHashSet<String>()
        var shown: List<Contact> = all

        val listAdapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int): Any = shown[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = (convertView ?: layoutInflater.inflate(
                    android.R.layout.simple_list_item_multiple_choice, parent, false
                )) as CheckedTextView
                val c = shown[position]
                v.text = c.name + "\n" + c.number
                v.isChecked = selected.contains(digitsKey(c.number))
                return v
            }
        }

        val search = EditText(this)
        search.hint = "Search name or number"
        search.setSingleLine()

        val listView = ListView(this)
        listView.adapter = listAdapter
        listView.setOnItemClickListener { _, _, pos, _ ->
            val k = digitsKey(shown[pos].number)
            if (!selected.remove(k)) selected.add(k)
            listAdapter.notifyDataSetChanged()
        }

        search.doAfterTextChanged { t ->
            val q = t?.toString()?.trim().orEmpty()
            shown = if (q.isEmpty()) all
            else all.filter { it.name.contains(q, ignoreCase = true) || it.number.contains(q) }
            listAdapter.notifyDataSetChanged()
        }

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(32, 16, 32, 0)
        box.addView(search)
        box.addView(
            listView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.density * 380).toInt()
            )
        )

        AlertDialog.Builder(this)
            .setTitle("Select people")
            .setView(box)
            .setPositiveButton("Add selected") { _, _ ->
                addSelectedContacts(all.filter { selected.contains(digitsKey(it.number)) })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addSelectedContacts(picked: List<Contact>) {
        // remove empty placeholder rows first
        for (i in container.childCount - 1 downTo 0) {
            val r = container.getChildAt(i)
            if (r.findViewById<EditText>(R.id.etName).text.isBlank() &&
                r.findViewById<EditText>(R.id.etPhone).text.isBlank()
            ) container.removeViewAt(i)
        }
        val existing = HashSet<String>()
        for (i in 0 until container.childCount) {
            existing.add(digitsKey(container.getChildAt(i).findViewById<EditText>(R.id.etPhone).text.toString()))
        }
        var added = 0
        var skipped = 0
        for (c in picked) {
            if (!existing.add(digitsKey(c.number))) { skipped++; continue }
            if (container.childCount >= MAX_PEOPLE) {
                Toast.makeText(this, "Limit of $MAX_PEOPLE people reached", Toast.LENGTH_LONG).show()
                break
            }
            addRow(c.name, c.number)
            added++
        }
        if (container.childCount == 0) addRow()
        updateCount()
        Toast.makeText(
            this,
            "Added $added" + if (skipped > 0) " ($skipped already in list)" else "",
            Toast.LENGTH_LONG
        ).show()
    }

    // Fallback: opens the normal Messages app for each person with the text pre-filled.
    private fun onManualClicked() {
        if (!manualActive) {
            val template = etTemplate.text.toString().trim()
            if (template.isEmpty()) {
                Toast.makeText(this, "Write a message first", Toast.LENGTH_SHORT).show()
                return
            }
            val people = collectPeople() ?: return
            if (people.isEmpty()) {
                Toast.makeText(this, "Add at least one person", Toast.LENGTH_SHORT).show()
                return
            }
            savePrefs()
            manualPeople = people
            manualTemplate = template
            manualIdx = 0
            manualActive = true
        }
        openManual()
    }

    private fun openManual() {
        val p = manualPeople[manualIdx]
        val text = manualTemplate.replace("{name}", p.name)
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${p.phone}")).putExtra("sms_body", text)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open Messages: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        manualIdx++
        if (manualIdx < manualPeople.size) {
            btnManual.text = "Next: ${manualPeople[manualIdx].name} (${manualIdx + 1}/${manualPeople.size})"
            tvStatus.text = "Opened message for ${p.name}. Tap Send in Messages, come back here, then tap Next."
        } else {
            manualActive = false
            btnManual.text = "Send one by one via Messages app"
            tvStatus.text = "Last message opened for ${p.name}. Tap Send in Messages to finish."
        }
    }

    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()

    private fun sendAll(template: String, people: List<Person>) {
        btnSend.isEnabled = false
        handedOver = 0
        total = people.size
        synchronized(failed) { failed.clear() }
        lastError = ""
        showStatus()

        Thread {
            val sms = smsManager()
            people.forEachIndexed { index, p ->
                try {
                    val text = template.replace("{name}", p.name)
                    // divideMessage handles Odia (UCS-2) and long English text automatically
                    val parts = sms.divideMessage(text)
                    val intent = Intent(ACTION_SENT).setPackage(packageName).putExtra("name", p.name)
                    val pi = PendingIntent.getBroadcast(
                        this, index, intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    val sentIntents = ArrayList<PendingIntent>().apply { repeat(parts.size) { add(pi) } }
                    sms.sendMultipartTextMessage(p.phone, null, parts, sentIntents, null)
                    handedOver++
                } catch (e: Exception) {
                    lastError = "${e.javaClass.simpleName}: ${e.message}"
                    synchronized(failed) { failed.add(p.name) }
                }
                runOnUiThread { showStatus() }
                Thread.sleep(DELAY_MS)
            }
            runOnUiThread {
                btnSend.isEnabled = true
                showStatus()
            }
        }.start()
    }

    companion object {
        private const val MAX_PEOPLE = 50
        private const val DELAY_MS = 1500L
        private const val ACTION_SENT = "com.abhijeet.bulksms.SMS_SENT"
        private const val KEY_TEMPLATE = "template"
        private const val KEY_PEOPLE = "people"
    }
}
