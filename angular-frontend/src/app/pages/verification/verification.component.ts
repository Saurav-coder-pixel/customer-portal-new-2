import { Component, OnInit } from "@angular/core";
import { FormBuilder, FormGroup, Validators } from "@angular/forms";
import { CustomerService } from "../../services/customer.service";

const GSTIN_STATE_CODES: Record<string, string> = {
  "01":"Jammu and Kashmir","02":"Himachal Pradesh","03":"Punjab","04":"Chandigarh",
  "05":"Uttarakhand","06":"Haryana","07":"Delhi","08":"Rajasthan","09":"Uttar Pradesh",
  "10":"Bihar","11":"Sikkim","12":"Arunachal Pradesh","13":"Nagaland","14":"Manipur",
  "15":"Mizoram","16":"Tripura","17":"Meghalaya","18":"Assam","19":"West Bengal",
  "20":"Jharkhand","21":"Odisha","22":"Chhattisgarh","23":"Madhya Pradesh","24":"Gujarat",
  "25":"Daman and Diu","26":"Dadra and Nagar Haveli","27":"Maharashtra","28":"Andhra Pradesh",
  "29":"Karnataka","30":"Goa","31":"Lakshadweep","32":"Kerala","33":"Tamil Nadu",
  "34":"Puducherry","35":"Andaman and Nicobar Islands","36":"Telangana","37":"Andhra Pradesh (New)",
  "38":"Ladakh"
};

@Component({
  selector: "app-verification",
  standalone: false,
  templateUrl: "./verification.component.html",
  styleUrls: ["./verification.component.css"]
})
export class VerificationComponent implements OnInit {
  form!: FormGroup;
  verificationType: "customer" | "gstin" = "customer";
  loading = false;
  customers: any[] | null = null;
  error = "";

  constructor(
    private fb: FormBuilder,
    private customerService: CustomerService
  ) {}

  ngOnInit(): void {
    this.form = this.fb.group({ code: ["", Validators.required] });
  }

  setType(type: "customer" | "gstin"): void {
    this.verificationType = type;
    this.form.reset();
    this.customers = null;
    this.error = "";
  }

  getStateName(gstin: string): string {
    const code = gstin?.substring(0, 2);
    return code ? (GSTIN_STATE_CODES[code] || "Unknown") : "";
  }

  getGstins(gstinNumbers: string): string[] {
    if (!gstinNumbers) return [];
    return gstinNumbers.replace(/[\[\]"\s]/g, '').split(',').filter(g => g);
  }

  onSubmit(): void {
    this.error = "";
    this.customers = null;
    const code = this.form.value.code?.trim();
    if (!code) {
      this.error = `Please enter a ${this.verificationType === "gstin" ? "GSTIN" : "Customer Code"}.`;
      return;
    }
    this.loading = true;
    const obs = this.verificationType === "gstin"
      ? this.customerService.lookupCustomerByGstin(code)
      : this.customerService.lookupOldCustomer(code);

    obs.subscribe({
      next: (data: any) => {
        this.loading = false;
        this.customers = Array.isArray(data) ? data : [data];
      },
      error: (err: any) => {
        this.loading = false;
        this.error = err?.error?.message || err?.message || "Code not found.";
      }
    });
  }

  reset(): void {
    this.form.reset();
    this.customers = null;
    this.error = "";
  }
}