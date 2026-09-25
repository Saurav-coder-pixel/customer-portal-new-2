import { Component, OnInit, AfterViewInit, ViewChild, ElementRef } from "@angular/core";
import { FormBuilder, FormGroup, Validators } from "@angular/forms";
import { Router } from "@angular/router";
import { AuthService } from "../../services/auth.service";

const CAPTCHA_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";

@Component({
  selector: "app-login",
  standalone: false,
  templateUrl: "./login.component.html",
  styleUrls: ["./login.component.css"]
})
export class LoginComponent implements OnInit, AfterViewInit {
  @ViewChild("captchaCanvas") captchaCanvasRef!: ElementRef<HTMLCanvasElement>;

  loginForm!: FormGroup;
  captchaText = "";
  showPassword = false;
  loading = false;
  error = "";

  constructor(
    private fb: FormBuilder,
    private authService: AuthService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.loginForm = this.fb.group({
      username: ["", Validators.required],
      password: ["", Validators.required],
      captchaInput: ["", Validators.required]
    });
  }

  ngAfterViewInit(): void {
    this.refreshCaptcha();
  }

  refreshCaptcha(): void {
    this.captchaText = this.generateCaptcha(6);
    this.loginForm.patchValue({ captchaInput: "" });
    requestAnimationFrame(() => this.drawCaptcha());
  }

  private generateCaptcha(len: number): string {
    let s = "";
    for (let i = 0; i < len; i++)
      s += CAPTCHA_CHARS[Math.floor(Math.random() * CAPTCHA_CHARS.length)];
    return s;
  }

  private drawCaptcha(): void {
    const canvas = this.captchaCanvasRef?.nativeElement;
    if (!canvas) return;
    const ctx = canvas.getContext("2d")!;
    const w = canvas.width, h = canvas.height;

    ctx.fillStyle = "#1e293b";
    ctx.fillRect(0, 0, w, h);

    for (let i = 0; i < 5; i++) {
      ctx.strokeStyle = `hsla(${Math.random() * 360}, 60%, 50%, 0.35)`;
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(Math.random() * w, Math.random() * h);
      ctx.lineTo(Math.random() * w, Math.random() * h);
      ctx.stroke();
    }
    for (let i = 0; i < 30; i++) {
      ctx.fillStyle = `hsla(${Math.random() * 360}, 50%, 55%, 0.45)`;
      ctx.beginPath();
      ctx.arc(Math.random() * w, Math.random() * h, 1.2, 0, Math.PI * 2);
      ctx.fill();
    }

    const fontSize = 26;
    ctx.font = `bold italic ${fontSize}px 'Courier New', monospace`;
    ctx.textBaseline = "middle";
    const text = this.captchaText;
    const totalWidth = ctx.measureText(text).width;
    let x = (w - totalWidth) / 2;
    const colors = ["#ef4444","#f59e0b","#22c55e","#3b82f6","#a855f7","#ec4899"];
    for (const ch of text) {
      ctx.save();
      const angle = (Math.random() - 0.5) * 0.35;
      const yOff = (Math.random() - 0.5) * 8;
      ctx.translate(x, h / 2 + yOff);
      ctx.rotate(angle);
      ctx.fillStyle = colors[Math.floor(Math.random() * colors.length)];
      ctx.fillText(ch, 0, 0);
      ctx.restore();
      x += ctx.measureText(ch).width + 2;
    }
  }

  togglePassword(): void { this.showPassword = !this.showPassword; }

  navigateSignUp(): void { this.router.navigate(["/signup"]); }

  onSubmit(): void {
    this.error = "";
    const { username, password, captchaInput } = this.loginForm.value;

    if (!username?.trim()) { this.error = "Please enter your username"; return; }
    if (!password)          { this.error = "Please enter your password"; return; }
    if (!captchaInput?.trim()) { this.error = "Please enter the captcha"; return; }
    if (captchaInput.trim() !== this.captchaText) {
      this.error = "Captcha does not match. Please try again.";
      this.refreshCaptcha();
      return;
    }

    this.loading = true;
    this.authService.login({ username: username.trim(), password }).subscribe({
      next: (res) => {
        this.loading = false;
        if (res.success) {
          this.router.navigate(["/register"]);
        } else {
          this.error = res.message || "Login failed.";
          this.refreshCaptcha();
        }
      },
      error: (err) => {
        this.loading = false;
        this.error = err?.error?.message || "Login failed. Please try again.";
        this.refreshCaptcha();
      }
    });
  }
}