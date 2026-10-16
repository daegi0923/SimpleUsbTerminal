package de.kai_morich.simple_usb_terminal.plc;

import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.EditText;
import androidx.appcompat.app.AlertDialog;

/** 앱 전용 SC 호기 번호를 입력하고 보관합니다. */
public final class ScSettings {
    /** 마지막으로 저장한 SC 호기 번호를 조회합니다. */
    public static String get(Context context) {
        return context.getSharedPreferences("plc", Context.MODE_PRIVATE).getString("scNumber", "");
    }

    /** 형식 검증을 통과한 번호만 저장하고 화면에 변경을 알립니다. */
    public static void edit(Context context, Runnable saved) {
        EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(3)});
        input.setHint("300 ~ 399");
        input.setText(get(context));
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle("SC 호기 번호")
                .setMessage("3으로 시작하는 숫자 3자리를 입력해 주세요.")
                .setView(input).setNegativeButton("취소", null).setPositiveButton("저장", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String number = input.getText().toString();
            if (!ScNumber.isValid(number)) {
                input.setError("300~399 범위의 숫자 3자리를 입력해 주세요.");
                return;
            }
            context.getSharedPreferences("plc", Context.MODE_PRIVATE).edit().putString("scNumber", number).apply();
            saved.run();
            dialog.dismiss();
        }));
        dialog.show();
    }
}
