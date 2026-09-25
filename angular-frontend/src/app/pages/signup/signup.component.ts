import { Component, OnInit } from "@angular/core";
import { FormBuilder, FormGroup, Validators } from "@angular/forms";
import { Router } from "@angular/router";
import { AuthService } from "../../services/auth.service";

@Component({
  selector: "app-signup",
  standalone: false,
  templateUrl: "./signup.component.html",
  styleUrls: ["./signup.component.css"]
})
export class SignupComponent implements OnInit {
  form!: FormGroup;
  showPassword = false;
  showConfirm = false;
  loading = false;
  error = "";
  success = "";

  constructor(
    private fb: FormBuilder,
    private authService: AuthService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.form = this.fb.group({
      email:           ["", [Validators.required, Validators.email]],
      username:        ["", Validators.required],
      password:        ["", Validators.required],
      confirmPassword: ["", Validators.required]
    });
  }

  onSubmit(): void {
    this.error = "";
    this.success = "";
    const val = this.form.value;
    if (!val.email || !val.username || !val.password || !val.confirmPassword) {
      this.error = "All fields are required.";
      return;
    }
    if (val.password !== val.confirmPassword) {
      this.error = "Passwords do not match.";
      return;
    }
    this.loading = true;
    this.authService.register(val).subscribe({
      next: (res) => {
        this.loading = false;
        if (res.success) {
          this.success = res.message;
          setTimeout(() => this.router.navigate(["/login"]), 1800);
        } else {
          this.error = res.message;
        }
      },
      error: (err) => {
        this.loading = false;
        this.error = err?.error?.message || "Registration failed.";
      }
    });
  }

  goLogin(): void { this.router.navigate(["/login"]); }
}