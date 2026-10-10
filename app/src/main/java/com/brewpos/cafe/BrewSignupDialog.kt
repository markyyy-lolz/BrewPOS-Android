package com.brewpos.cafe

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Customer account creation is not BrewPOS activation. A verified email account
 * remains unprivileged until Azurate issues a matching signed BP1 and approves
 * the separate merchant organization/device.
 */
@Composable
fun BrewSignupDialog(onClose: () -> Unit, onRegistered: () -> Unit) {
    val ctx: Context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deviceId = remember {
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
    }
    var business by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    var otpVerified by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(if(submitted) "Account submitted" else "Create BrewPOS account",
            fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max=450.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if(submitted) {
                    Text("Your email is verified. Your request is waiting for Azurate approval.",
                        color=Color(0xFF196753),fontWeight=FontWeight.SemiBold)
                    Text("The purchased Trial, Monthly, or Lifetime license will be assigned by Azurate. This account does not activate the POS until you enter the signed BP1 code.")
                } else {
                    Text("Register your business using an email one-time code. No plan is activated automatically.",
                        fontSize=13.sp)
                    OutlinedTextField(
                        value=business,onValueChange={ if(!sent) business=it.take(80) },
                        enabled=!sent && !busy,label={Text("Business name")},
                        modifier=Modifier.fillMaxWidth(),singleLine=true)
                    OutlinedTextField(
                        value=email,onValueChange={ if(!sent) email=it.trim().take(254) },
                        enabled=!sent && !busy,label={Text("Email address")},
                        modifier=Modifier.fillMaxWidth(),singleLine=true)
                    OutlinedTextField(value=deviceId,onValueChange={},
                        readOnly=true,label={Text("Android Device ID (license binding)")},
                        modifier=Modifier.fillMaxWidth(),singleLine=true)
                    if(sent) {
                        Text("Enter the OTP emailed to ${email}.",fontSize=13.sp)
                        OutlinedTextField(
                            value=code,onValueChange={ code=it.filter(Char::isDigit).take(6) },
                            label={Text("Email OTP (6 digits)")},singleLine=true,
                            modifier=Modifier.fillMaxWidth(),enabled=!busy)
                        TextButton(onClick={
                            sent=false;code="";message=""
                        },enabled=!busy && !otpVerified) { Text("Use another email") }
                    }
                    Text("Please check spam if no email arrives. OTP delivery requires BrewPOS Supabase Auth email configuration.",
                        fontSize=11.sp)
                }
                if(message.isNotBlank()) Text(message,
                    color=if(submitted) Color(0xFF196753) else MaterialTheme.colorScheme.error,
                    fontSize=12.sp)
            }
        },
        confirmButton = {
            if(submitted) Button(onClick=onClose) { Text("Done") }
            else if(!sent) Button(
                enabled=!busy && business.trim().length in 2..80 &&
                    email.contains("@") && deviceId.matches(Regex("^[0-9a-fA-F]{16}$")),
                onClick={
                    busy=true;message=""
                    scope.launch {
                        runCatching { BrewCloud.sendRegistrationCode(email) }
                            .onSuccess { sent=true;message="" }
                            .onFailure { message=it.message ?: "Could not send email code" }
                        busy=false
                    }
                }) { Text(if(busy) "Sending…" else "Send verification code") }
            else Button(
                enabled=!busy && (otpVerified || code.length == 6),
                onClick={
                    busy=true;message=""
                    scope.launch {
                        runCatching {
                            if(!otpVerified) {
                                BrewCloud.loginWithCode(ctx,email,code)
                                otpVerified=true
                                code=""
                            }
                            // A failed network request can now safely retry without reusing a consumed OTP.
                            BrewCloud.requestAccountApproval(ctx,business,deviceId)
                        }
                            .onSuccess {
                                code="";submitted=true
                                message="Registration request sent. Check back after Azurate assigns your license."
                                onRegistered()
                            }
                            .onFailure { message=it.message ?: "Verification or registration failed" }
                        busy=false
                    }
                }) { Text(if(busy) "Verifying…" else if(otpVerified) "Retry account request" else "Verify OTP & create account") }
        },
        dismissButton = {
            if(!submitted) TextButton(onClick=onClose,enabled=!busy) { Text("Cancel") }
        }
    )
}
